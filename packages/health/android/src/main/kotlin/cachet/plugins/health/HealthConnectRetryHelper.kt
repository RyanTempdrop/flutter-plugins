package cachet.plugins.health

import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Utility class for handling Health Connect API rate limiting with exponential backoff retry logic.
 * Health Connect enforces rate limits on API calls, and exceeding the quota results in
 * RemoteException with "Rate limited" message. This helper provides retry mechanisms to
 * gracefully handle these scenarios.
 */
object HealthConnectRetryHelper {

    private const val TAG = "FLUTTER_HEALTH"
    private const val DEFAULT_MAX_RETRIES = 3
    private const val DEFAULT_INITIAL_DELAY_MS = 1000L
    private const val DEFAULT_MAX_DELAY_MS = 10000L
    private const val BACKOFF_MULTIPLIER = 2.0

    /**
     * Executes a Health Connect API call with exponential backoff retry logic.
     * If a rate limit error is encountered, the call will be retried after an
     * exponentially increasing delay.
     *
     * @param T The return type of the API call
     * @param maxRetries Maximum number of retry attempts (default: 3)
     * @param initialDelayMs Initial delay in milliseconds before first retry (default: 1000ms)
     * @param maxDelayMs Maximum delay cap in milliseconds (default: 10000ms)
     * @param operationName Name of the operation for logging purposes
     * @param block The suspend function containing the Health Connect API call
     * @return The result of the API call, or throws the exception if all retries fail
     */
    suspend fun <T> executeWithRetry(
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
        maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
        operationName: String = "Health Connect operation",
        block: suspend () -> T
    ): T {
        var currentDelayMs = initialDelayMs
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: RemoteException) {
                lastException = e
                if (isRateLimitError(e) && attempt < maxRetries - 1) {
                    Log.w(
                        TAG,
                        "$operationName rate limited (attempt ${attempt + 1}/$maxRetries). " +
                                "Waiting ${currentDelayMs}ms before retry..."
                    )
                    delay(currentDelayMs)
                    currentDelayMs = (currentDelayMs * BACKOFF_MULTIPLIER).toLong()
                        .coerceAtMost(maxDelayMs)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                // For non-RemoteException errors, check if the cause is a rate limit error
                if (e.cause is RemoteException && isRateLimitError(e.cause as RemoteException)) {
                    lastException = e
                    if (attempt < maxRetries - 1) {
                        Log.w(
                            TAG,
                            "$operationName rate limited (attempt ${attempt + 1}/$maxRetries). " +
                                    "Waiting ${currentDelayMs}ms before retry..."
                        )
                        delay(currentDelayMs)
                        currentDelayMs = (currentDelayMs * BACKOFF_MULTIPLIER).toLong()
                            .coerceAtMost(maxDelayMs)
                    } else {
                        throw e
                    }
                } else {
                    throw e
                }
            }
        }

        // This should not be reached, but just in case
        throw lastException ?: IllegalStateException("Retry loop completed without result")
    }

    /**
     * Executes a Health Connect API call with retry logic, returning null on failure
     * instead of throwing an exception. Useful for operations where failure is acceptable.
     *
     * @param T The return type of the API call
     * @param maxRetries Maximum number of retry attempts
     * @param initialDelayMs Initial delay before first retry
     * @param maxDelayMs Maximum delay cap
     * @param operationName Name of the operation for logging
     * @param block The suspend function containing the Health Connect API call
     * @return The result of the API call, or null if all retries fail
     */
    suspend fun <T> executeWithRetryOrNull(
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
        maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
        operationName: String = "Health Connect operation",
        block: suspend () -> T
    ): T? {
        return try {
            executeWithRetry(maxRetries, initialDelayMs, maxDelayMs, operationName, block)
        } catch (e: Exception) {
            Log.e(TAG, "$operationName failed after $maxRetries attempts: ${e.message}")
            null
        }
    }

    /**
     * Checks if the given RemoteException is a rate limit error.
     *
     * @param e The RemoteException to check
     * @return true if this is a rate limit error, false otherwise
     */
    private fun isRateLimitError(e: RemoteException): Boolean {
        val message = e.message ?: return false
        return message.contains("Rate limited", ignoreCase = true) ||
                message.contains("quota", ignoreCase = true)
    }
}
