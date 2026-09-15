package com.gd.rain.llm

import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.EmptyUsage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt

/** Which ceiling of a pool an ask is measured against. */
public enum class LlmClass {
    /** Background work. */
    BULK,

    /** Work a person is waiting for; its ceiling is at least as deep as bulk's. */
    INTERACTIVE,
}

public enum class LlmRole { SYSTEM, USER, ASSISTANT }

public data class LlmMessage(
    public val role: LlmRole,
    public val content: String,
) {
    public companion object {
        public fun system(content: String): LlmMessage = LlmMessage(LlmRole.SYSTEM, content)

        public fun user(content: String): LlmMessage = LlmMessage(LlmRole.USER, content)

        public fun assistant(content: String): LlmMessage = LlmMessage(LlmRole.ASSISTANT, content)
    }
}

/**
 * A provider-neutral ask. [temperature] and [maxOutputTokens] are sent when stated; when not, the
 * `ChatModel`'s own configured options apply. Provider-specific options are added by
 * [LlmRequestCustomizer] beans.
 */
public data class LlmRequest(
    public val messages: List<LlmMessage>,
    public val temperature: Double? = null,
    public val maxOutputTokens: Int? = null,
) {
    init {
        require(messages.isNotEmpty()) { "an ask holds at least one message" }
        require(maxOutputTokens == null || maxOutputTokens >= 1) { "maxOutputTokens is at least 1, got $maxOutputTokens" }
    }
}

public data class LlmUsage(
    public val promptTokens: Int,
    public val completionTokens: Int,
)

public data class LlmResponse(
    public val text: String,
    /** The provider's finish reason, when it reported one. */
    public val finishReason: String?,
    /** Token usage, when the provider reported it. */
    public val usage: LlmUsage?,
)

/**
 * Adds what a provider needs to a request's options — a provider-specific options type, extra body
 * fields, a reasoning switch. Applied in bean order, each to the previous one's result.
 */
public fun interface LlmRequestCustomizer {
    public fun customize(
        request: LlmRequest,
        options: ChatOptions,
    ): ChatOptions
}

/** Builds the Spring AI prompt of a request and reads the answer back; shared by the gateway and `smoke-llm`. */
internal class LlmPrompts(
    private val model: String,
    private val customizers: List<LlmRequestCustomizer>,
) {
    fun prompt(request: LlmRequest): Prompt {
        val messages: List<Message> =
            request.messages.map { message ->
                when (message.role) {
                    LlmRole.SYSTEM -> SystemMessage(message.content)
                    LlmRole.USER -> UserMessage(message.content)
                    LlmRole.ASSISTANT -> AssistantMessage(message.content)
                }
            }
        val builder = ChatOptions.builder()
        builder.model(model)
        request.temperature?.let { builder.temperature(it) }
        request.maxOutputTokens?.let { builder.maxTokens(it) }
        val options = customizers.fold(builder.build()) { current, customizer -> customizer.customize(request, current) }
        return Prompt(messages, options)
    }

    fun answer(response: ChatResponse): LlmResponse {
        val generation: Generation = response.result ?: throw LlmEmptyAnswer("it holds no generation")
        val text: String? = generation.output.text
        if (text.isNullOrBlank()) throw LlmEmptyAnswer("its text is empty")
        val finishReason: String? = generation.metadata.finishReason
        val usage = response.metadata.usage
        val reported = if (usage is EmptyUsage) null else usage
        val promptTokens: Int? = reported?.promptTokens
        val completionTokens: Int? = reported?.completionTokens
        return LlmResponse(
            text = text,
            finishReason = finishReason,
            usage = if (promptTokens != null && completionTokens != null) LlmUsage(promptTokens, completionTokens) else null,
        )
    }
}
