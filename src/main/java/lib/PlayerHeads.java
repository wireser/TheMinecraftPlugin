package lib;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.json.JSONObject;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

import net.kyori.adventure.text.Component;
import utils.Validator;

import org.json.JSONArray;

/**
 * Utility class for handling player head retrieval with skin caching
 */
public class PlayerHeads {
    
    // Configuration constants - easily modifiable for future changes
    private static final String MOJANG_API_BASE_URL = "https://api.mojang.com";
    private static final String SESSION_SERVER_BASE_URL = "https://sessionserver.mojang.com";
    private static final String USER_AGENT = "MinecraftPlugin/1.0";
    private static final int CONNECTION_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 15;
	
    // HTTP client for API calls
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
    	.connectTimeout(java.time.Duration.ofSeconds(CONNECTION_TIMEOUT_SECONDS))
        .build();
    
    /**
     * Main function to get a player head ItemStack for the given username
     * 
     * This function handles the complete process of retrieving a player head:
     * 1. Validates the input username
     * 2. Checks if player is online first (fastest method)
     * 3. If offline, checks cache database for stored skin
     * 4. If not in cache, fetches from Mojang API
     * 5. Applies skin to ItemStack and returns result
     * 
     * @param username The Minecraft username to get the head for
     * @return ItemStack with player head, or null if any step fails
     */
    public static ItemStack getPlayerHead(String username) {
    	
        try {
        	
            // Step 1: Validate input username
            if(!Validator.isValidMinecraftUsername(username))
            	throw new IllegalArgumentException("Invalid input");
            
            // Step 2: Try to get skin from online player first (fastest method)
            String skinData = getSkinFromOnlinePlayer(username);
            
            // Step 3: If online player not available, try cache or Mojang API
            if(skinData == null)
                skinData = getSkinFromCacheOrMojang(username);

            // Step 4: If we have skin data, create and return the player head
            if(skinData != null)
                return createPlayerHeadItem(username, skinData);
            
        }
        
        catch(IllegalArgumentException e) {
            Bukkit.getLogger().warning("Invalid username for player head: " + username + " - " + e.getMessage());
        }
        
        catch(Exception e) {
            Bukkit.getLogger().severe("Unexpected error getting player head for " + username + ": " + e.getMessage());
        }
        
        return null;
        
    }
    
    /**
     * Creates a player head ItemStack with the specified skin data
     * 
     * Uses the modern PaperAPI to create stackable player heads with proper skins
     */
    private static ItemStack createPlayerHeadItem(String displayName, String skinData) throws Exception {
        
    	if(Validator.validateString(displayName))
            throw new IllegalArgumentException("Display name cannot be null or empty");

        if(Validator.validateString(skinData))
            throw new IllegalArgumentException("Skin data cannot be null or empty");
        
        
        try {
        	
            // Create the base player head item
            ItemStack headItem = new ItemStack(Material.PLAYER_HEAD, 1);
            SkullMeta skullMeta = (SkullMeta) headItem.getItemMeta();
            
            if(skullMeta == null)
                throw new Exception("Failed to get SkullMeta from player head item");
            
            // Create profile with consistent UUID for same skins (makes them stackable)
            // Use UUID based on the skin data to ensure same skins have same UUID
            UUID profileUuid = UUID.nameUUIDFromBytes(skinData.getBytes());
            PlayerProfile profile = Bukkit.createProfile(profileUuid, "Head");
            
            // Create texture property - use the complete skin data from Mojang
            ProfileProperty textureProperty = new ProfileProperty("textures", skinData);
            
            // Set the texture property
            Set<ProfileProperty> properties = new HashSet<>();
            properties.add(textureProperty);
            profile.setProperties(properties);
            
            // Apply the profile to the skull meta
            skullMeta.setPlayerProfile(profile);
            
            // Set display name
            Component displayComponent = Component.text(displayName);
            skullMeta.displayName(displayComponent);
            
            headItem.setItemMeta(skullMeta);
            
            return headItem;
            
        }
        
        catch(Exception e) {
            throw new Exception("Failed to create player head item: " + e.getMessage(), e);
        }
        
    }
    
    /**
     * Attempts to get skin data from an online player
     */
    private static String getSkinFromOnlinePlayer(String username) {
        Player onlinePlayer = Bukkit.getPlayerExact(username);
        
        if(onlinePlayer == null || !onlinePlayer.isOnline())
        	return null;

		try {
			PlayerProfile profile = onlinePlayer.getPlayerProfile();
			if(profile != null) {
				ProfileProperty textureProperty = profile.getProperties().stream()
						.filter(prop -> "textures".equals(prop.getName()))
						.findFirst()
						.orElse(null);
		
				if(textureProperty != null) {
					String textureValue = textureProperty.getValue();
					// Cache the skin for future use
					uploadSkinToCache(username, textureValue);
					return textureValue;
				}
			}
		}
		
		catch(Exception e) {
			Bukkit.getLogger().warning("Failed to get skin from online player " + username + ": " + e.getMessage());
		}
        
        return null;
    }
    
    /**
     * Gets skin data from cache or falls back to Mojang API
     */
    private static String getSkinFromCacheOrMojang(String username) throws Exception {
       
    	// Step 1: Try to get from cache first
        String cachedSkin = downloadSkinFromCache(username);
        if(cachedSkin != null) {
            return cachedSkin;
        }
        
        // Step 2: Validate user exists before making API call
        if(!checkUserExistsInMojang(username))
            throw new IllegalArgumentException("Player does not exist: " + username);
        
        // Step 3: Fetch complete skin data from Mojang API
        String skinData = fetchCompleteSkinDataFromMojang(username);
        if(skinData != null) {
            // Cache the skin for future use
            uploadSkinToCache(username, skinData);
            return skinData;
        }
        
        return null;
    }
    
    /**
     * Fetches complete skin data from Mojang API including both value and signature
     * This is the key fix for offline player skins
     */
    private static String fetchCompleteSkinDataFromMojang(String username) throws Exception {
        try {
        	
            // Step 1: Get UUID from username
            String uuid = getUUIDFromUsername(username);
            
            if(uuid == null)
                throw new Exception("Could not get UUID for username: " + username);

            // Step 2: Get profile data with textures from UUID
            String profileUrl = SESSION_SERVER_BASE_URL + "/session/minecraft/profile/" + uuid;
            
            HttpRequest profileRequest = HttpRequest.newBuilder()
                    .uri(URI.create(profileUrl))
                    .timeout(java.time.Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            
            CompletableFuture<String> future = HTTP_CLIENT.sendAsync(profileRequest, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if(response.statusCode() != 200)
                            throw new RuntimeException("HTTP " + response.statusCode() + " for profile data: " + response.body());

                        try {
                        	
                            JSONObject profileJson = new JSONObject(response.body());
                            JSONArray properties = profileJson.getJSONArray("properties");
                            
                            // Find the textures property and return the complete base64 value
                            for(int i = 0; i < properties.length(); i++) {
                            	
                                JSONObject property = properties.getJSONObject(i);
                                if("textures".equals(property.getString("name"))) {
                                	
                                    // Return the complete base64 texture data
                                    String textureValue = property.getString("value");
                                    
                                    // Verify this is valid base64 texture data
                                    if(isValidTextureData(textureValue))
                                        return textureValue;
                                    else
                                        throw new RuntimeException("Invalid texture data received from Mojang");
                                    
                                }
                                
                            }
                            
                            throw new RuntimeException("No textures property found in profile data");
                            
                        }
                        
                        catch(Exception e) {
                            throw new RuntimeException("Failed to parse profile data: " + e.getMessage(), e);
                        }
                    });
            
            return future.get(READ_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            
        }
        
        catch(java.util.concurrent.TimeoutException e) {
            throw new Exception("Timeout while fetching skin from Mojang for: " + username);
        }
        
        catch(Exception e) {
            throw new Exception("Failed to fetch skin from Mojang for: " + username, e);
        }
    }
    
    /**
     * Validates that the texture data is proper base64 that contains skin information
     */
    private static boolean isValidTextureData(String textureData) {
        
    	if(textureData == null || textureData.length() < 100)
            return false;

        try {
        	
            // Try to decode the base64
            String decoded = new String(Base64.getDecoder().decode(textureData));
            
            // Check if it contains skin-related JSON structure
            return decoded.contains("textures") && decoded.contains("SKIN");
            
        }
        
        catch(Exception e) {
            return false;
        }
        
    }
    
    /**
     * Gets UUID from username using Mojang API
     */
    private static String getUUIDFromUsername(String username) throws Exception {
        String apiUrl = MOJANG_API_BASE_URL + "/users/profiles/minecraft/" + username;
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(java.time.Duration.ofSeconds(CONNECTION_TIMEOUT_SECONDS))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        
        CompletableFuture<String> future = HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        		.thenApply(response -> {
                	
                    if(response.statusCode() == 200) {
                        JSONObject json = new JSONObject(response.body());
                        return json.getString("id");
                    }
                    
                    else if(response.statusCode() == 204 || response.statusCode() == 404)
                        return null;
                    else
                        throw new RuntimeException("HTTP " + response.statusCode() + " - " + response.body());
                    
                });
        
        return future.get(CONNECTION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }
    
    /**
     * Checks if a username exists in Mojang's database
     */
    public static boolean checkUserExistsInMojang(String username) throws Exception {
    	
        try {
        	
            String apiUrl = MOJANG_API_BASE_URL + "/users/profiles/minecraft/" + username;
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(java.time.Duration.ofSeconds(CONNECTION_TIMEOUT_SECONDS))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            
            CompletableFuture<Boolean> future = HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        int statusCode = response.statusCode();
                        
                        if(statusCode == 200)
                            return true;
                        
                        else if(statusCode == 204 || statusCode == 404)
                            return false;
                        
                        else
                            throw new RuntimeException("HTTP " + statusCode + " - " + response.body());
                    });
            
            return future.get(CONNECTION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            
        }
        
        catch(java.util.concurrent.TimeoutException e) {
            throw new Exception("Timeout while checking user existence for: " + username);
        }
        
        catch(Exception e) {
            throw new Exception("Failed to check if user exists: " + username, e);
        }
        
    }
    
    /**
     * Uploads a skin texture to the cache database
     */
    private static void uploadSkinToCache(String username, String skinData) {
        try {
            upload(username, skinData, System.currentTimeMillis());
        } catch (Exception e) {
            Bukkit.getLogger().warning("Failed to upload skin to cache for " + username + ": " + e.getMessage());
        }
    }
    
    /**
     * Downloads a skin texture from the cache database
     */
    private static String downloadSkinFromCache(String username) {
        try {
            return download(username);
        } catch (Exception e) {
            Bukkit.getLogger().warning("Failed to download skin from cache for " + username + ": " + e.getMessage());
            return null;
        }
    }
    
    // =========================================================================
    // DUMMY FUNCTIONS - YOU NEED TO IMPLEMENT THESE
    // =========================================================================
    
    /**
     * Dummy upload function - YOU IMPLEMENT THIS
     */
    private static void upload(String username, String skinData, long timestamp) {
        // TODO: Implement your database storage logic here
        Bukkit.getLogger().info("DUMMY UPLOAD: Would store skin for " + username);
    }
    
    /**
     * Dummy download function - YOU IMPLEMENT THIS
     */
    private static String download(String username) {
        // TODO: Implement your database retrieval logic here
        Bukkit.getLogger().info("DUMMY DOWNLOAD: Would retrieve skin for " + username);
        return null;
    }
}