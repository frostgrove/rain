package com.gd.rain.sample.catalog

import com.gd.rain.boot.runtime.ConditionalOnRainRole
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.boot.seed.Seeder
import com.gd.rain.core.id.IdGenerator
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal
import java.time.Clock

/** Idempotent catalogue seed: product fixtures make the web demo useful immediately after `rain seed`. */
class ProductSeeder(private val dsl: DSLContext, private val ids: IdGenerator, private val clock: Clock) : Seeder {
    override val name = "catalogue.products"
    override val order = 30

    override fun seed() {
        rows.forEach { product ->
            dsl.insertInto(DSL.table(DSL.name("public", "products")))
                .set(DSL.field("id"), ids.next())
                .set(DSL.field("name"), product.name)
                .set(DSL.field("sku"), product.sku)
                .set(DSL.field("category"), product.category)
                .set(DSL.field("price"), product.price)
                .set(DSL.field("stock"), product.stock)
                .set(DSL.field("status"), product.status)
                .set(DSL.field("supplier"), product.supplier)
                .set(DSL.field("notes"), product.notes)
                .set(DSL.field("updated_at"), clock.instant())
                .onConflict(DSL.field("sku"))
                .doNothing()
                .execute()
        }
    }

    private data class SeedProduct(val name: String, val sku: String, val category: String, val price: BigDecimal, val stock: Int, val status: String, val supplier: String, val notes: String)
    private companion object {
        val rows = listOf(
            SeedProduct("Axiom Desk Lamp", "AXM-LMP-01", "Lighting", BigDecimal("129.00"), 42, "active", "Northstar Goods", "Anodised aluminium; cable bundles arrive every Thursday."),
            SeedProduct("Field Notebook Set", "FLD-NBK-03", "Stationery", BigDecimal("24.00"), 168, "active", "Paper Works", "Three pack. Reorder point is 60 units."),
            SeedProduct("Meridian Stool", "MRD-STL-02", "Furniture", BigDecimal("310.00"), 7, "active", "Form & Grain", "Low stock. Next container is due in October."),
            SeedProduct("Civic Wall Clock", "CVC-CLK-04", "Objects", BigDecimal("86.00"), 0, "draft", "Atelier Eight", "Awaiting product photography and final dial proof."),
            SeedProduct("Harbor Throw", "HBR-THR-01", "Textiles", BigDecimal("148.00"), 31, "active", "Loom & Line", "Wool-cotton blend."),
        )
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnRainRole(RuntimeRole.SEEDER)
class ProductSeedConfiguration {
    @Bean fun catalogueProductSeeder(dsl: DSLContext, ids: IdGenerator, clock: Clock): Seeder = ProductSeeder(dsl, ids, clock)
}
