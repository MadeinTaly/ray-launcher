package dev.raylauncher;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the user can grant notification access, which Android requires before RAY may see and control
 * other apps' media sessions. It does not read or keep notifications.
 */
public class RayMediaListener extends NotificationListenerService {
}
