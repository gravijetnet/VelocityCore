package net.gravijet.velocity.core.util;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

import java.lang.reflect.Method;

public class CompatibilityHelper {
    
    private static Method playerSendMessageMethod = null;
    private static Method sourceSendMessageMethod = null;
    private static boolean reflectionFailed = false;
    
    public static void sendMessage(Player player, Component component) {
        if (player == null || component == null) return;
        
        // Try direct method first (should work if Adventure classes are compatible)
        try {
            player.sendMessage(component);
            return;
        } catch (NoSuchMethodError e) {
            // Fall back to reflection
            try {
                if (playerSendMessageMethod == null && !reflectionFailed) {
                    // Try to find the correct method
                    for (Method method : Player.class.getMethods()) {
                        if (method.getName().equals("sendMessage") && 
                            method.getParameterCount() == 1 &&
                            method.getParameterTypes()[0].getName().contains("Component")) {
                            playerSendMessageMethod = method;
                            break;
                        }
                    }
                    if (playerSendMessageMethod == null) {
                        reflectionFailed = true;
                    }
                }
                
                if (playerSendMessageMethod != null) {
                    playerSendMessageMethod.invoke(player, component);
                    return;
                }
            } catch (Exception ex) {
                // Last resort: send plain text
                try {
                    // Try to get plain text from component
                    String text = component.toString();
                    player.sendMessage(Component.text(text));
                } catch (Exception ex2) {
                    player.sendMessage(Component.text("Error sending message"));
                }
            }
        }
    }

    public static void sendMessage(CommandSource source, Component component) {
        if (source == null || component == null) return;

        // Try direct method first
        try {
            source.sendMessage(component);
            return;
        } catch (NoSuchMethodError e) {
            // Fall back to reflection
            try {
                if (sourceSendMessageMethod == null && !reflectionFailed) {
                    // Try to find the correct method
                    for (Method method : CommandSource.class.getMethods()) {
                        if (method.getName().equals("sendMessage") &&
                            method.getParameterCount() == 1 &&
                            method.getParameterTypes()[0].getName().contains("Component")) {
                            sourceSendMessageMethod = method;
                            break;
                        }
                    }
                    if (sourceSendMessageMethod == null) {
                        reflectionFailed = true;
                    }
                }

                if (sourceSendMessageMethod != null) {
                    sourceSendMessageMethod.invoke(source, component);
                    return;
                }
            } catch (Exception ex) {
                // Last resort: send plain text
                try {
                    String text = component.toString();
                    source.sendMessage(Component.text(text));
                } catch (Exception ex2) {
                    source.sendMessage(Component.text("Error sending message"));
                }
            }
        }
    }
}