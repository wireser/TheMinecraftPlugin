package enums;

/**
 * Describes the friendship between the requesting player and another player.
 *
 * <p>This value is derived from the friendship row and the requesting player's
 * ID. It is not stored in the database.</p>
 */
public enum FriendshipStatus {

    /** No friendship row or pending request exists between the players. */
    NONE,

    /** The requesting player sent a request which is waiting for acceptance. */
    OUTGOING_REQUEST,

    /** The other player sent a request which the requesting player may accept. */
    INCOMING_REQUEST,

    /** The friendship request has been accepted by both sides. */
    ACCEPTED,

    /** Persistent storage failed, so no relationship decision is safe. */
    UNAVAILABLE
}
