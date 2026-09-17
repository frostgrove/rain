package com.gd.rain.i18n

import java.math.BigDecimal
import java.time.ZoneId

/** Whether a rendered value receives automatic bidi isolation. */
public enum class Presentation {
    AUTOMATIC_ISOLATION,
    NO_ISOLATION,
}

/** Explicit immutable rendering inputs. A resolution from another snapshot is not accepted. */
public data class ViewSpec(
    public val resolution: LocaleResolution.Resolved,
    public val zone: ZoneId,
    public val presentation: Presentation = Presentation.AUTOMATIC_ISOLATION,
    public val overlays: List<CatalogOverlay> = emptyList(),
    public val observer: I18nObserver = I18nObserver.NONE,
)

/** The structural kind of one safe rendered part. A rich result is never trusted HTML. */
public enum class RichPartKind {
    TEXT,
    VALUE,
    MARKUP_OPEN,
    MARKUP_CLOSE,
    MARKUP_STANDALONE,
    BIDI_ISOLATE,
}

/** One text/value/markup boundary in a rendered message. */
public data class RichPart(
    public val kind: RichPartKind,
    public val text: String? = null,
    public val name: String? = null,
    public val id: String? = null,
    public val direction: PartDirection? = null,
)

/** Complete render output with actual template locale and a cache-safe, value-sensitive render key. */
public data class RenderedMessage(
    public val text: String,
    public val parts: List<RichPart>,
    public val templateLocale: LocaleTag,
    public val resolvedLocale: LocaleTag,
    public val resolutionSource: LocaleSource,
    public val resolutionReason: LocaleResolutionReason,
    public val snapshot: CatalogRef,
    public val contract: MessageContractRef,
    public val winningLayer: RenderLayer,
    public val overlay: CatalogOverlayRef?,
    public val renderKey: Digest,
)

/** A bounded diagnostic trace that deliberately excludes values, message text, principal and tenant. */
public data class RenderExplanation(
    public val key: MessageKey,
    public val snapshot: CatalogRef,
    public val resolvedLocale: LocaleTag,
    public val templateLocale: LocaleTag,
    public val source: LocaleSource,
    public val reason: LocaleResolutionReason,
    public val winningLayer: RenderLayer = RenderLayer.CATALOG,
    public val overlay: CatalogOverlayRef? = null,
)

/** A render failure is typed and contains no rendered input value. */
public class MessageRenderException(
    message: String,
) : IllegalArgumentException(message)

/** One explicit locale/zone-bound renderer over exactly one immutable [CatalogSnapshot]. */
public class I18nView internal constructor(
    private val snapshot: CatalogSnapshot,
    public val spec: ViewSpec,
) {
    /** Exact immutable catalog behind this view; useful when a caller deliberately pins a durable hand-off. */
    public val catalog: CatalogRef get() = snapshot.reference

    /** The complete low-level formatter for this view's already explicit locale and zone. */
    public fun formatter(): I18nFormatter = I18nFormatter(spec.resolution.locale, spec.zone, snapshot.limits)

    /** Renders only a message bound to this snapshot's exact message contract. */
    public fun render(message: DeferredMessage): RenderedMessage =
        try {
            val record =
                snapshot.message(message.contract.key)
                    ?: throw MessageRenderException("message ${message.contract.key} is not declared by this snapshot")
            require(record.contract == message.contract) { "message ${message.contract.key} was bound for another catalog contract" }
            val selected = template(record)
            val collector = RenderCollector(snapshot.limits)
            val arguments = message.arguments.entries.associate { it.name to it.value }
            renderTemplate(selected.template, arguments, collector)
            val output = collector.finish()
            RenderedMessage(
                text = output.text,
                parts = output.parts,
                templateLocale = selected.locale,
                resolvedLocale = spec.resolution.locale,
                resolutionSource = spec.resolution.source,
                resolutionReason = spec.resolution.reason,
                snapshot = snapshot.reference,
                contract = message.contract,
                winningLayer = selected.layer,
                overlay = selected.overlay,
                renderKey = renderKey(message, selected),
            ).also { rendered ->
                spec.observer.emit(
                    I18nObservation(
                        I18nObservationOperation.RENDER,
                        I18nObservationOutcome.RENDERED,
                        rendered.resolutionReason,
                        rendered.winningLayer,
                    ),
                )
            }
        } catch (failure: IllegalArgumentException) {
            spec.observer.emit(
                I18nObservation(
                    I18nObservationOperation.RENDER,
                    I18nObservationOutcome.FAILED,
                    spec.resolution.reason,
                ),
            )
            throw failure
        }

    /** Explains a successful resolution without reading or serializing the message's typed values. */
    public fun explain(message: DeferredMessage): RenderExplanation {
        val record =
            snapshot.message(message.contract.key)
                ?: throw MessageRenderException("message ${message.contract.key} is not declared by this snapshot")
        require(record.contract == message.contract) { "message ${message.contract.key} was bound for another catalog contract" }
        val selected = template(record)
        return RenderExplanation(
            key = message.contract.key,
            snapshot = snapshot.reference,
            resolvedLocale = spec.resolution.locale,
            templateLocale = selected.locale,
            source = spec.resolution.source,
            reason = spec.resolution.reason,
            winningLayer = selected.layer,
            overlay = selected.overlay,
        )
    }

    private fun template(record: MessageRecord): SelectedTemplate {
        snapshot.localeResolver.fallbackChain(spec.resolution.locale).forEach { locale ->
            spec.overlays
                .sortedByDescending { it.layer.precedence }
                .forEach { overlay ->
                    overlay.translation(record.spec.key, locale)?.let {
                        return SelectedTemplate(locale, it.template, overlay.layer.renderLayer(), overlay.reference)
                    }
                }
            record.translations[locale]?.let { return SelectedTemplate(locale, it.template, RenderLayer.CATALOG, null) }
        }
        return SelectedTemplate(snapshot.sourceLocale, record.sourceTemplate, RenderLayer.CATALOG, null)
    }

    private fun renderTemplate(
        template: Mf2Template,
        arguments: Map<String, MessageValue>,
        collector: RenderCollector,
        inherited: Map<String, ResolvedMf2Value> = emptyMap(),
    ) {
        val bindings = inherited.toMutableMap()
        template.declarations.forEach { declaration -> bindings[declaration.name] = declarationValue(declaration, arguments, bindings) }
        val selected = template.select?.let { select -> selectVariant(select, bindings) }
        if (selected != null) {
            renderTemplate(selected.template, arguments, collector, bindings)
            return
        }
        template.nodes.forEach { node -> renderNode(node, arguments, bindings, collector) }
    }

    private fun renderNode(
        node: Mf2Node,
        arguments: Map<String, MessageValue>,
        bindings: Map<String, ResolvedMf2Value>,
        collector: RenderCollector,
    ) {
        when (node) {
            is Mf2Node.Text -> {
                collector.text(node.value)
            }

            is Mf2Node.Expression -> {
                val value = expressionValue(node, arguments, bindings)
                rejectUnsafeBidi(value.value)
                val formatted =
                    IcuFormatter(
                        spec.resolution.locale,
                        spec.zone,
                        snapshot.limits,
                    ).format(value.value, value.function, value.options)
                collector.value(formatted, value.metadata, spec.presentation)
            }

            is Mf2Node.MarkupOpen -> {
                collector.markup(RichPartKind.MARKUP_OPEN, node.name, node.metadata)
            }

            is Mf2Node.MarkupClose -> {
                collector.markup(RichPartKind.MARKUP_CLOSE, node.name, node.metadata)
            }

            is Mf2Node.MarkupStandalone -> {
                collector.markup(RichPartKind.MARKUP_STANDALONE, node.name, node.metadata)
            }
        }
    }

    private fun declarationValue(
        declaration: Mf2Declaration,
        arguments: Map<String, MessageValue>,
        bindings: Map<String, ResolvedMf2Value>,
    ): ResolvedMf2Value =
        when (val expression = declaration.expression) {
            is Mf2BindingExpression.Literal -> {
                ResolvedMf2Value(
                    MessageValue.Text(expression.value),
                    Mf2Function.STRING,
                    emptyMap(),
                    PartMetadata(),
                    declaration.selectMode,
                )
            }

            is Mf2BindingExpression.Value -> {
                val value =
                    if (declaration.kind == Mf2DeclarationKind.INPUT) {
                        val raw = requireNotNull(arguments[declaration.name]) { "bound input ${declaration.name} is missing" }
                        ResolvedMf2Value(
                            raw,
                            expression.expression.function,
                            expression.expression.options,
                            expression.expression.metadata,
                            declaration.selectMode,
                        )
                    } else {
                        expressionValue(expression.expression, arguments, bindings).copy(selectMode = declaration.selectMode)
                    }
                value
            }
        }

    private fun expressionValue(
        expression: Mf2Node.Expression,
        arguments: Map<String, MessageValue>,
        bindings: Map<String, ResolvedMf2Value>,
    ): ResolvedMf2Value {
        val inherited = bindings[expression.variable]
        if (inherited != null) {
            if (expression.function == null) {
                return inherited.copy(metadata = inherited.metadata.merge(expression.metadata))
            }
            return inherited.copy(
                function = expression.function,
                options = expression.options,
                metadata = inherited.metadata.merge(expression.metadata),
                selectMode = selectMode(expression.function, inherited.value),
            )
        }
        val value = requireNotNull(arguments[expression.variable]) { "bound argument ${expression.variable} is missing" }
        return ResolvedMf2Value(value, expression.function, expression.options, expression.metadata, selectMode(expression.function, value))
    }

    private fun selectVariant(
        select: Mf2Select,
        bindings: Map<String, ResolvedMf2Value>,
    ): Mf2Variant {
        val values = select.selectors.map { name -> requireNotNull(bindings[name]) { "selector $name is not declared" } }
        return requireNotNull(
            select.variants.firstOrNull { variant -> variant.keys.zip(values).all { (key, value) -> key.matches(value, formatter()) } },
        ) { "a compiled match has an all-wildcard fallback" }
    }

    internal data class ResolvedMf2Value(
        val value: MessageValue,
        val function: Mf2Function?,
        val options: Map<String, String>,
        val metadata: PartMetadata,
        val selectMode: Mf2SelectMode,
    )

    private fun renderKey(
        message: DeferredMessage,
        template: SelectedTemplate,
    ): Digest {
        val form = CanonicalForm()
        form.text(snapshot.digest.hex)
        form.text(message.contract.key.value)
        form.number(message.contract.revision)
        form.text(message.contract.digest.value.hex)
        form.text(spec.resolution.locale.value)
        form.text(template.locale.value)
        form.text(spec.zone.id)
        form.text(spec.presentation.name)
        form.text(template.layer.name)
        template.overlay?.let {
            form.text(it.layer.name)
            form.text(it.revision)
            form.text(it.digest.hex)
        }
        message.arguments.entries.sortedBy(MessageArgument::name).forEach { argument ->
            form.text(argument.name)
            appendValueIdentity(form, argument.value)
        }
        return Digest.sha256(form.bytes())
    }

    private data class SelectedTemplate(
        val locale: LocaleTag,
        val template: Mf2Template,
        val layer: RenderLayer,
        val overlay: CatalogOverlayRef?,
    )
}

/** Creates the full low-level renderer after verifying that the resolution was made by this snapshot. */
public fun CatalogSnapshot.view(spec: ViewSpec): I18nView {
    require(localeResolver.owns(spec.resolution)) { "a view resolution belongs to this exact catalog snapshot" }
    require(
        spec.overlays
            .map(CatalogOverlay::layer)
            .distinct()
            .size == spec.overlays.size,
    ) {
        "a view contains at most one overlay for each layer"
    }
    require(spec.overlays.all { it.base == reference }) { "a view overlay belongs to this exact catalog snapshot" }
    return I18nView(this, spec)
}

private fun Mf2SelectorKey.matches(
    resolved: I18nView.ResolvedMf2Value,
    formatter: I18nFormatter,
): Boolean {
    if (this is Mf2SelectorKey.Wildcard) return true
    require(this is Mf2SelectorKey.Exact)
    val value = resolved.selectionValue()
    return when (resolved.selectMode) {
        Mf2SelectMode.EXACT -> {
            exactSelectorValue(value) == this.value
        }

        Mf2SelectMode.CARDINAL, Mf2SelectMode.ORDINAL -> {
            val exact = numericSelectorValue(value)
            if (exact == this.value) {
                true
            } else {
                formatter.plural(value, if (resolved.selectMode == Mf2SelectMode.CARDINAL) PluralType.CARDINAL else PluralType.ORDINAL) ==
                    this.value
            }
        }
    }
}

private fun I18nView.ResolvedMf2Value.selectionValue(): MessageValue =
    if (function == Mf2Function.OFFSET) offsetValue(value, options.requiredDecimal("offset")) else value

private fun exactSelectorValue(value: MessageValue): String =
    when (value) {
        is MessageValue.Text -> java.text.Normalizer.normalize(value.value, java.text.Normalizer.Form.NFC)
        is MessageValue.BooleanValue -> value.value.toString()
        is MessageValue.IntegerValue -> value.value.toString()
        is MessageValue.UnsignedIntegerValue -> value.value.toString()
        is MessageValue.BigIntegerValue -> value.value.toString()
        is MessageValue.DecimalValue -> value.value.toPlainString()
        is MessageValue.MoneyValue -> value.amount.toPlainString()
        is MessageValue.DateValue -> value.value.toString()
        is MessageValue.InstantValue -> value.value.toString()
        is MessageValue.EnumValue -> value.value
        MessageValue.Null -> "null"
    }

private fun numericSelectorValue(value: MessageValue): String =
    when (value) {
        is MessageValue.IntegerValue,
        is MessageValue.UnsignedIntegerValue,
        is MessageValue.BigIntegerValue,
        is MessageValue.DecimalValue,
        is MessageValue.MoneyValue,
        -> exactSelectorValue(value)

        else -> throw MessageRenderException("a plural selector has a numeric value")
    }

private fun PartMetadata.merge(outer: PartMetadata): PartMetadata =
    PartMetadata(
        id = outer.id ?: id,
        direction = if (outer.direction == PartDirection.AUTO) direction else outer.direction,
    )

private fun selectMode(
    function: Mf2Function?,
    value: MessageValue,
): Mf2SelectMode =
    defaultSelectMode(
        function,
        when (value) {
            is MessageValue.Text -> ArgumentType.TEXT
            is MessageValue.BooleanValue -> ArgumentType.BOOLEAN
            is MessageValue.IntegerValue -> ArgumentType.INTEGER
            is MessageValue.UnsignedIntegerValue -> ArgumentType.UNSIGNED_INTEGER
            is MessageValue.BigIntegerValue -> ArgumentType.BIG_INTEGER
            is MessageValue.DecimalValue -> ArgumentType.DECIMAL
            is MessageValue.MoneyValue -> ArgumentType.MONEY
            is MessageValue.DateValue -> ArgumentType.DATE
            is MessageValue.InstantValue -> ArgumentType.INSTANT
            is MessageValue.EnumValue -> ArgumentType.ENUM
            MessageValue.Null -> ArgumentType.TEXT
        },
    )

private class RenderCollector(
    private val limits: I18nLimits,
) {
    private val text = StringBuilder()
    private val parts = mutableListOf<RichPart>()
    private var outputBytes: Long = 0

    fun text(value: String) {
        appendText(value, RichPartKind.TEXT)
    }

    fun value(
        value: String,
        metadata: PartMetadata,
        presentation: Presentation,
    ) {
        val direction = metadata.direction
        if (presentation == Presentation.AUTOMATIC_ISOLATION) {
            val isolate =
                when (direction) {
                    PartDirection.LTR -> '\u2066'
                    PartDirection.RTL -> '\u2067'
                    PartDirection.AUTO, PartDirection.INHERIT -> '\u2068'
                }
            appendText(isolate.toString(), RichPartKind.BIDI_ISOLATE)
            appendText(value, RichPartKind.VALUE, metadata)
            appendText("\u2069", RichPartKind.BIDI_ISOLATE)
        } else {
            appendText(value, RichPartKind.VALUE, metadata)
        }
    }

    fun markup(
        kind: RichPartKind,
        name: String,
        metadata: PartMetadata,
    ) {
        checkParts()
        parts += RichPart(kind = kind, name = name, id = metadata.id, direction = metadata.direction)
    }

    internal fun finish(): RenderedOutput = RenderedOutput(text.toString(), parts.toList())

    private fun appendText(
        value: String,
        kind: RichPartKind,
        metadata: PartMetadata = PartMetadata(),
    ) {
        val valueBytes = value.utf8Size().toLong()
        val nextBytes = outputBytes + valueBytes
        if (nextBytes > limits.maxOutputBytes) throw MessageRenderException("rendered output exceeds ${limits.maxOutputBytes} UTF-8 bytes")
        checkParts()
        text.append(value)
        outputBytes = nextBytes
        parts += RichPart(kind = kind, text = value, id = metadata.id, direction = metadata.direction)
    }

    private fun checkParts() {
        if (parts.size == limits.maxOutputParts) {
            throw MessageRenderException("rendered output exceeds ${limits.maxOutputParts} structural parts")
        }
    }
}

internal data class RenderedOutput(
    val text: String,
    val parts: List<RichPart>,
)

private class IcuFormatter(
    locale: LocaleTag,
    zone: ZoneId,
    limits: I18nLimits,
) {
    private val formatter = I18nFormatter(locale, zone, limits)

    fun format(
        value: MessageValue,
        function: Mf2Function?,
        options: Map<String, String>,
    ): String {
        val formatting = options.withoutUniversal()
        return when (function) {
            null, Mf2Function.STRING -> {
                default(value, formatting)
            }

            Mf2Function.NUMBER -> {
                formatter.number(value, formatting.numberOptions())
            }

            Mf2Function.INTEGER -> {
                formatter.integer(value, formatting.numberOptions())
            }

            Mf2Function.CURRENCY -> {
                formatter.money(
                    value as? MessageValue.MoneyValue ?: throw MessageRenderException("currency formatting requires a money value"),
                    formatting.numberOptions(setOf("currencySign")),
                    formatting.currencySign(),
                )
            }

            Mf2Function.PERCENT -> {
                formatter.percent(value, formatting.numberOptions())
            }

            Mf2Function.OFFSET -> {
                formatter.offset(value, formatting.requiredDecimal("offset"), formatting.numberOptions(setOf("offset")))
            }

            Mf2Function.UNIT -> {
                formatter.unit(value, UnitIdentifier.parse(formatting.required("unit")), formatting.numberOptions(setOf("unit")))
            }

            Mf2Function.DATE -> {
                date(value, formatting)
            }

            Mf2Function.TIME -> {
                formatter.time(
                    (value as? MessageValue.InstantValue)?.value ?: throw MessageRenderException(
                        "time formatting requires an instant value",
                    ),
                    formatting.dateStyle(),
                )
            }

            Mf2Function.DATETIME -> {
                formatter.dateTime(
                    (value as? MessageValue.InstantValue)?.value
                        ?: throw MessageRenderException("datetime formatting requires an instant value"),
                    formatting.dateStyle("dateStyle"),
                    formatting.dateStyle("timeStyle"),
                )
            }
        }
    }

    private fun default(
        value: MessageValue,
        options: Map<String, String>,
    ): String {
        require(options.isEmpty()) { "string formatting does not accept formatter options" }
        return when (value) {
            is MessageValue.Text -> value.value

            is MessageValue.BooleanValue -> value.value.toString()

            is MessageValue.EnumValue -> value.value

            is MessageValue.IntegerValue,
            is MessageValue.UnsignedIntegerValue,
            is MessageValue.BigIntegerValue,
            is MessageValue.DecimalValue,
            -> formatter.number(value)

            is MessageValue.MoneyValue -> formatter.money(value)

            is MessageValue.DateValue -> formatter.date(value.value)

            is MessageValue.InstantValue -> formatter.dateTime(value.value)

            MessageValue.Null -> ""
        }
    }

    private fun date(
        value: MessageValue,
        options: Map<String, String>,
    ): String =
        when (value) {
            is MessageValue.DateValue -> formatter.date(value.value, options.dateStyle())
            is MessageValue.InstantValue -> formatter.date(value.value, options.dateStyle())
            else -> throw MessageRenderException("date formatting requires a date or instant value")
        }
}

internal fun rejectUnsafeBidi(value: MessageValue) {
    val text =
        when (value) {
            is MessageValue.Text -> value.value
            is MessageValue.EnumValue -> value.value
            else -> return
        }
    if (text.any { it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }) {
        throw MessageRenderException("an untrusted value contains bidi direction controls")
    }
}

private fun appendValueIdentity(
    form: CanonicalForm,
    value: MessageValue,
) {
    when (value) {
        is MessageValue.Text -> {
            form.text("text")
            form.text(value.value)
        }

        is MessageValue.BooleanValue -> {
            form.text("boolean")
            form.boolean(value.value)
        }

        is MessageValue.IntegerValue -> {
            form.text("integer")
            form.text(value.value.toString())
        }

        is MessageValue.UnsignedIntegerValue -> {
            form.text("unsigned")
            form.text(value.value.toString())
        }

        is MessageValue.BigIntegerValue -> {
            form.text("bigint")
            form.text(value.value.toString())
        }

        is MessageValue.DecimalValue -> {
            form.text("decimal")
            form.text(value.value.toPlainString())
            form.number(value.value.scale())
        }

        is MessageValue.MoneyValue -> {
            form.text("money")
            form.text(value.amount.toPlainString())
            form.number(value.amount.scale())
            form.text(value.currency.currencyCode)
        }

        is MessageValue.DateValue -> {
            form.text("date")
            form.text(value.value.toString())
        }

        is MessageValue.InstantValue -> {
            form.text("instant")
            form.text(value.value.toString())
        }

        is MessageValue.EnumValue -> {
            form.text("enum")
            form.text(value.value)
        }

        MessageValue.Null -> {
            form.text("null")
        }
    }
}
