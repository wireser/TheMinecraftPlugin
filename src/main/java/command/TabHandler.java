package command;

import java.util.List;

import playerdata.Profile;

@FunctionalInterface
public interface TabHandler {
	
    /**
     * Provides tab-completion suggestions.
     *
     * @param profile the player (or null if we ever support console)
     * @param label   the used label/alias
     * @param args    the arguments
     * @return a list of suggestions (never null)
     */
    List<String> complete(Profile profile, String label, String[] args);
    
}