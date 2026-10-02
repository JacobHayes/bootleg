package dev.jacobhayes.bootleg.download

/**
 * Pure resume logic for ranged downloads: which headers to send for a partial file, and what to do
 * with the server's answer. No I/O, so it is JVM-tested exhaustively.
 */
object ResumePlanner {
    /** Validator captured from the first response, used for `If-Range` on later attempts. */
    data class Validator(
        val etag: String?,
        val lastModified: String?,
    )

    fun requestHeaders(
        partialSizeBytes: Long,
        validator: Validator?,
    ): Map<String, String> {
        require(partialSizeBytes >= 0) { "partialSizeBytes must be >= 0, was $partialSizeBytes" }
        if (partialSizeBytes == 0L) return emptyMap()
        val headers = linkedMapOf("Range" to "bytes=$partialSizeBytes-")
        // If-Range only accepts strong ETags (RFC 9110 13.1.5); fall back to Last-Modified.
        val strongEtag = validator?.etag?.takeUnless { it.startsWith("W/") }
        val ifRange = strongEtag ?: validator?.lastModified
        if (ifRange != null) headers["If-Range"] = ifRange
        return headers
    }

    sealed interface Action {
        /** Server honoured the range: append the body to the partial file, until the file is [totalBytes] long. */
        data class Append(
            val offsetBytes: Long,
            val totalBytes: Long,
        ) : Action

        /** Server sent the whole entity: truncate the partial file and write from 0. */
        data class Restart(
            val totalBytes: Long?,
        ) : Action

        /** Discard the body and partial, then request again from 0 (range didn't line up). */
        data class RetryFromZero(
            val reason: String,
        ) : Action

        /** 416 whose Content-Range total equals the partial's length: the partial is probably complete, hash it. */
        data object Reverify : Action

        data class Fail(
            val reason: String,
            val retryable: Boolean,
        ) : Action
    }

    /**
     * [expectedTotalBytes] is the full size known from earlier (catalog or a previous response); it lets
     * a 206 with an unknown total still be checked for completeness.
     */
    fun decide(
        requestedOffsetBytes: Long,
        status: Int,
        contentRange: String?,
        contentLengthBytes: Long?,
        expectedTotalBytes: Long? = null,
    ): Action {
        require(requestedOffsetBytes >= 0) { "requestedOffsetBytes must be >= 0, was $requestedOffsetBytes" }
        require(expectedTotalBytes == null || expectedTotalBytes >= 0) { "negative expected total: $expectedTotalBytes" }
        return when (status) {
            200 -> Action.Restart(contentLengthBytes?.takeIf { it >= 0 })
            206 -> decidePartial(requestedOffsetBytes, contentRange, expectedTotalBytes)
            416 -> decideUnsatisfiable(requestedOffsetBytes, contentRange)
            404, 410 -> Action.Fail("Not found on server (HTTP $status)", retryable = false)
            408, 425, 429, in 500..599 -> Action.Fail("Server error (HTTP $status)", retryable = true)
            else -> Action.Fail("Unexpected HTTP $status", retryable = false)
        }
    }

    private fun decidePartial(
        requestedOffsetBytes: Long,
        contentRange: String?,
        expectedTotalBytes: Long?,
    ): Action {
        val range =
            ContentRange.parse(contentRange)
                ?: return Action.RetryFromZero("206 without a usable Content-Range")
        if (range.startBytes == null || range.startBytes != requestedOffsetBytes) {
            return Action.RetryFromZero("206 for offset ${range.startBytes}, wanted $requestedOffsetBytes")
        }
        if (range.totalBytes != null && expectedTotalBytes != null && range.totalBytes != expectedTotalBytes) {
            return Action.RetryFromZero("server now has ${range.totalBytes} bytes, expected $expectedTotalBytes")
        }
        // Without a known total a short body would look complete, and with no checksum nothing would catch it.
        val total =
            range.totalBytes ?: expectedTotalBytes
                ?: return Action.RetryFromZero("206 without a total size; can't tell when the file is complete")
        if (range.endBytes != null && range.endBytes >= total) return Action.RetryFromZero("206 range ends past the total size")
        return Action.Append(requestedOffsetBytes, total)
    }

    private fun decideUnsatisfiable(
        requestedOffsetBytes: Long,
        contentRange: String?,
    ): Action {
        if (requestedOffsetBytes == 0L) return Action.Fail("HTTP 416 without a range request", retryable = false)
        val total = ContentRange.parse(contentRange)?.totalBytes
        return when (total) {
            requestedOffsetBytes -> Action.Reverify
            // Without `bytes */N` nothing says the partial is complete; start over rather than trust it.
            null -> Action.RetryFromZero("416 without the server's size")
            else -> Action.RetryFromZero("partial is $requestedOffsetBytes bytes but the server has $total")
        }
    }

    /** `bytes 100-199/1000`, `bytes 100-199/*` or `bytes */1000`. */
    data class ContentRange(
        val startBytes: Long?,
        val endBytes: Long?,
        val totalBytes: Long?,
    ) {
        companion object {
            private val pattern = Regex("""^\s*bytes\s+(?:(\d+)-(\d+)|\*)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)

            fun parse(header: String?): ContentRange? {
                val match = pattern.matchEntire(header ?: return null) ?: return null
                val (start, end, total) = match.destructured
                val range =
                    ContentRange(
                        startBytes = start.toLongOrNull(),
                        endBytes = end.toLongOrNull(),
                        totalBytes = total.toLongOrNull(),
                    )
                if (range.startBytes != null && range.endBytes != null && range.endBytes < range.startBytes) return null
                if (range.startBytes == null && range.totalBytes == null) return null
                return range
            }
        }
    }
}
