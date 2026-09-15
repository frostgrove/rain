package com.gd.rain.web.config

import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.servlet.autoconfigure.MultipartProperties
import org.springframework.util.unit.DataSize

/** While the container parses multipart bodies, its limits are the body limit for them, so they fit inside rain's. */
class MultipartLimitsCheckTest {
    private val bodyLimit = DataSize.ofMegabytes(8)

    private fun multipart(
        request: DataSize,
        file: DataSize,
        enabled: Boolean = true,
    ) = MultipartProperties().apply {
        maxRequestSize = request
        maxFileSize = file
        isEnabled = enabled
    }

    @Test
    fun `no multipart support, or multipart disabled, has nothing to check`() {
        assertThat(MultipartLimitsCheck(null, bodyLimit).problems()).isEmpty()
        assertThat(MultipartLimitsCheck(multipart(DataSize.ofGigabytes(1), DataSize.ofGigabytes(1), enabled = false), bodyLimit).problems())
            .isEmpty()
    }

    @Test
    fun `limits within the body limit pass, up to exactly the body limit`() {
        assertThat(MultipartLimitsCheck(multipart(bodyLimit, DataSize.ofMegabytes(2)), bodyLimit).problems()).isEmpty()
    }

    @Test
    fun `Spring Boot's own request size above a small body limit contradicts it`() {
        val problems = MultipartLimitsCheck(MultipartProperties(), DataSize.ofKilobytes(16)).problems()

        assertThat(problems.map { it.path to it.code })
            .containsExactly(MultipartLimitsCheck.MAX_REQUEST_SIZE to ProblemCode.CONTRADICTS)
    }

    @Test
    fun `unlimited sizes are refused, and a file larger than its request contradicts it`() {
        assertThat(
            MultipartLimitsCheck(multipart(DataSize.ofBytes(-1), DataSize.ofBytes(-1)), bodyLimit).problems().map {
                it.path to
                    it.code
            },
        ).containsExactly(
            MultipartLimitsCheck.MAX_REQUEST_SIZE to ProblemCode.INVALID,
            MultipartLimitsCheck.MAX_FILE_SIZE to ProblemCode.INVALID,
        )
        assertThat(
            MultipartLimitsCheck(multipart(DataSize.ofMegabytes(4), DataSize.ofMegabytes(5)), bodyLimit).problems().map {
                it.path to
                    it.code
            },
        ).containsExactly(MultipartLimitsCheck.MAX_FILE_SIZE to ProblemCode.CONTRADICTS)
    }
}
