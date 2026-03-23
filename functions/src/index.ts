import { onCall, HttpsError } from "firebase-functions/v2/https";
import { logger } from "firebase-functions/v2";
import * as admin from "firebase-admin";

// Initialize Firebase Admin SDK
admin.initializeApp();

// Message categories matching the Android app
type MessageCategory = "FLUTTER" | "NUDGE" | "HEARTBEAT" | "LIFELINE";

interface SendHeartRequest {
  targetFcmToken: string;
  category: MessageCategory;
  note?: string;
  senderUid: string;
  encrypted?: boolean;  // Whether the note is E2E encrypted
}

/**
 * Cloud Function to send a heart signal (push notification) to a partner.
 * 
 * This function validates the request, then sends an FCM message with
 * high priority to ensure it bypasses battery optimization and is
 * delivered immediately.
 */
export const sendHeart = onCall(async (request) => {
  // Verify the user is authenticated
  if (!request.auth) {
    throw new HttpsError(
      "unauthenticated",
      "Must be authenticated to send messages"
    );
  }

  const data = request.data as SendHeartRequest;
  const { targetFcmToken, category, note, senderUid, encrypted } = data;

  // Validate required fields
  if (!targetFcmToken) {
    throw new HttpsError(
      "invalid-argument",
      "targetFcmToken is required"
    );
  }

  if (!category) {
    throw new HttpsError(
      "invalid-argument",
      "category is required"
    );
  }

  // Validate category
  const validCategories: MessageCategory[] = [
    "FLUTTER", "NUDGE", "HEARTBEAT", "LIFELINE"
  ];
  if (!validCategories.includes(category)) {
    throw new HttpsError(
      "invalid-argument",
      `Invalid category: ${category}`
    );
  }

  // Verify the sender UID matches the authenticated user
  if (senderUid !== request.auth.uid) {
    throw new HttpsError(
      "permission-denied",
      "Sender UID must match authenticated user"
    );
  }

  // Construct the FCM message
  // Using data-only message so the app handles display
  const message: admin.messaging.Message = {
    token: targetFcmToken,
    data: {
      category: category,
      note: note || "",
      sender_uid: senderUid,
      timestamp: Date.now().toString(),
      encrypted: (encrypted ?? false).toString(),  // Pass through E2E encryption flag
    },
    android: {
      // High priority ensures delivery even in Doze mode
      priority: "high",
      // TTL: 1 hour for Lifeline, 10 minutes for others
      ttl: category === "LIFELINE" ? 3600000 : 600000,
    },
  };

  try {
    // Send the FCM message
    const response = await admin.messaging().send(message);
    
    logger.info("Message sent successfully", {
      messageId: response,
      category: category,
      senderUid: senderUid,
    });

    return {
      success: true,
      messageId: response,
    };
  } catch (error) {
    logger.error("Failed to send message", {
      error: error,
      targetToken: targetFcmToken.substring(0, 20) + "...",
      category: category,
    });

    // Check for specific FCM errors
    if (error instanceof Error) {
      if (error.message.includes("not-registered") || 
          error.message.includes("invalid-registration-token")) {
        throw new HttpsError(
          "not-found",
          "Partner's device token is invalid or expired. They may need to reinstall the app."
        );
      }
    }

    throw new HttpsError(
      "internal",
      "Failed to send message. Please try again."
    );
  }
});

/**
 * Cloud Function to update FCM token when it changes.
 * This keeps the user's token current in Firestore.
 */
export const updateFcmToken = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError(
      "unauthenticated",
      "Must be authenticated"
    );
  }

  const { fcmToken } = request.data as { fcmToken: string };
  if (!fcmToken) {
    throw new HttpsError(
      "invalid-argument",
      "fcmToken is required"
    );
  }

  try {
    await admin.firestore()
      .collection("users")
      .doc(request.auth.uid)
      .set({
        fcmToken: fcmToken,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      }, { merge: true });

    return { success: true };
  } catch (error) {
    logger.error("Failed to update FCM token", { error });
    throw new HttpsError(
      "internal",
      "Failed to update token"
    );
  }
});
