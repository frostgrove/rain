package com.gd.rain.llm

import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A deterministic `ChatModel`: every call takes the next scripted step, and a call with no step left
 * fails the test. No network, no provider.
 */
public class ScriptedChatModel : ChatModel {
    private val steps = ConcurrentLinkedQueue<(Prompt) -> ChatResponse>()
    private val seen = CopyOnWriteArrayList<Prompt>()

    /** Every prompt the model was called with, in order. */
    public val prompts: List<Prompt> get() = seen.toList()

    public fun answers(
        text: String,
        finishReason: String = "stop",
        promptTokens: Int? = null,
        completionTokens: Int? = null,
    ): ScriptedChatModel =
        then {
            val metadata = ChatResponseMetadata.builder()
            if (promptTokens != null && completionTokens != null) metadata.usage(DefaultUsage(promptTokens, completionTokens))
            ChatResponse(
                listOf(Generation(AssistantMessage(text), ChatGenerationMetadata.builder().finishReason(finishReason).build())),
                metadata.build(),
            )
        }

    public fun fails(failure: RuntimeException): ScriptedChatModel = then { throw failure }

    public fun then(step: (Prompt) -> ChatResponse): ScriptedChatModel {
        steps += step
        return this
    }

    override fun call(prompt: Prompt): ChatResponse {
        seen += prompt
        val step = steps.poll() ?: error("the scripted chat model was called ${seen.size} times and has no step left")
        return step(prompt)
    }
}
