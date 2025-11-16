package managers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SimpleLanguageManager implements LanguageManager {

    private final Map<String, String> messages = new ConcurrentHashMap<>();

    public void set(String key, String value) {
        messages.put(key, value);
    }

    @Override
    public String get(String key, String fallback) {
        return messages.getOrDefault(key, fallback);
    }

    @Override
    public String getFormatted(String key, String fallback, Object... arguments) {
        String pattern = messages.getOrDefault(key, fallback);
        try {
            return String.format(pattern, arguments);
        } catch (Exception e) {
            // In case someone screws up the format string
            return pattern;
        }
    }
    
}