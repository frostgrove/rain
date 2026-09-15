package com.gd.rain.llm

import com.gd.rain.boot.command.CommandOutput
import com.gd.rain.boot.command.RainCommand
import com.gd.rain.boot.runtime.CommandDeclaration
import com.gd.rain.boot.runtime.RuntimeRole
import org.springframework.ai.chat.model.ChatModel
import org.springframework.boot.ApplicationArguments

/** `smoke-llm`: one round trip to the model, then exit 0 when it answered and 1 when it did not. */
public class SmokeLlmCommandDeclaration : CommandDeclaration {
    override val name: String = NAME
    override val description: String = "ask the configured chat model one question, then exit"
    override val roles: Set<RuntimeRole> = emptySet()
    override val properties: Map<String, String> = emptyMap()

    public companion object {
        public const val NAME: String = "smoke-llm"
    }
}

/**
 * Asks the model directly: no slot and no breaker, so the answer is about whether the model server is
 * reachable even when every slot is taken or the breaker is open. The request goes through the
 * application's customizers, so provider options apply as they do to real asks.
 */
public class SmokeLlmCommand(
    private val settings: LlmSettings?,
    private val model: () -> ChatModel?,
    private val customizers: List<LlmRequestCustomizer>,
) : RainCommand {
    override val name: String = SmokeLlmCommandDeclaration.NAME

    override fun run(
        arguments: ApplicationArguments,
        output: CommandOutput,
    ): Int {
        val enabled = settings ?: return refuse(output, "${LlmProperties.ENABLED} is not true, so there is no model to ask")
        val chat = model() ?: return refuse(output, "the application has no single ChatModel bean")
        val prompts = LlmPrompts(enabled.model, customizers)
        return try {
            val answer = prompts.answer(chat.call(prompts.prompt(REQUEST)))
            output.out.println("smoke-llm: model ${enabled.model} answered with ${answer.text.length} characters")
            0
        } catch (failure: RuntimeException) {
            refuse(output, failure.message ?: failure.javaClass.name)
        }
    }

    private fun refuse(
        output: CommandOutput,
        why: String,
    ): Int {
        output.err.println("smoke-llm: $why")
        return 1
    }

    private companion object {
        val REQUEST = LlmRequest(listOf(LlmMessage.system("Reply with the single word OK."), LlmMessage.user("Health check.")))
    }
}
