package com.gd.rain.sample.catalog

import com.gd.rain.boot.runtime.ConditionalOnRainRole
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.crud.Action
import com.gd.rain.crud.ActionAccess
import com.gd.rain.crud.CallerLookup
import com.gd.rain.crud.CrudResource
import com.gd.rain.crud.ResourcePolicy
import com.gd.rain.crud.ScopeRule
import com.gd.rain.crud.persistence.JooqResourceStore
import com.gd.rain.crud.persistence.RowReader
import com.gd.rain.crud.query.FieldGrant
import com.gd.rain.crud.query.FieldKind
import com.gd.rain.crud.query.Operator
import com.gd.rain.crud.query.Pagination
import com.gd.rain.crud.query.QueryRules
import com.gd.rain.crud.query.QueryShape
import com.gd.rain.crud.query.ResourceSchema
import com.gd.rain.crud.query.SchemaField
import com.gd.rain.crud.query.SortKey
import com.gd.rain.crud.query.TableName
import com.gd.rain.crud.web.CountBody
import com.gd.rain.crud.web.CrudMvc
import com.gd.rain.crud.web.CrudOperation
import com.gd.rain.crud.web.MountedResource
import com.gd.rain.crud.web.PageBody
import com.gd.rain.sample.config.TicketProperties
import com.gd.rain.web.route.DeclaresItsOwnAccess
import com.gd.rain.web.route.EndpointDeclaration
import jakarta.servlet.http.HttpServletRequest
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Product rows are deliberately a normal rain-crud resource, not a bespoke admin endpoint. */
typealias Product = Map<String, Any?>

object ProductPermissions {
    const val READ = "product.read"
}

object ProductFields {
    val ID = SchemaField("id", "id", FieldKind.UUID, nullable = false)
    val NAME = SchemaField("name", "name", FieldKind.TEXT, nullable = false)
    val SKU = SchemaField("sku", "sku", FieldKind.TEXT, nullable = false)
    val CATEGORY = SchemaField("category", "category", FieldKind.TEXT, nullable = false)
    val PRICE = SchemaField("price", "price", FieldKind.DECIMAL, nullable = false)
    val STOCK = SchemaField("stock", "stock", FieldKind.INT, nullable = false)
    val STATUS = SchemaField("status", "status", FieldKind.TEXT, nullable = false)
    val SUPPLIER = SchemaField("supplier", "supplier", FieldKind.TEXT, nullable = false)
    val NOTES = SchemaField("notes", "notes", FieldKind.TEXT, nullable = true)
    val UPDATED_AT = SchemaField("updatedAt", "updated_at", FieldKind.TIMESTAMP, nullable = false)

    val SCHEMA =
        ResourceSchema(
            name = "products",
            table = TableName("public", "products"),
            id = ID,
            fields = listOf(NAME, SKU, CATEGORY, PRICE, STOCK, STATUS, SUPPLIER, NOTES, UPDATED_AT),
            version = null,
        )
}

object ProductDeclarations {
    const val PREFIX = "/v1/products"
    private const val READ_WHY = "catalogue records are visible to product operators"

    // Every option the demo table sends has an explicit, index-backed shape.
    val SHAPES =
        listOf(
            QueryShape.of(SortKey.NONE),
            QueryShape.of(SortKey.parse("name")),
            QueryShape.of(SortKey.parse("-name")),
            QueryShape.of(SortKey.parse("category")),
            QueryShape.of(SortKey.parse("-category")),
            QueryShape.of(SortKey.parse("price")),
            QueryShape.of(SortKey.parse("-price")),
            QueryShape.of(SortKey.parse("stock")),
            QueryShape.of(SortKey.parse("-stock")),
            QueryShape.of(SortKey.parse("status")),
            QueryShape.of(SortKey.parse("-status")),
            QueryShape.of(SortKey.NONE, "name" to Operator.EQ),
            QueryShape.of(SortKey.NONE, "category" to Operator.EQ),
            QueryShape.of(SortKey.NONE, "status" to Operator.EQ),
            QueryShape.of(SortKey.NONE, "category" to Operator.EQ, "status" to Operator.EQ),
            QueryShape.of(SortKey.NONE, "stock" to Operator.GTE),
            QueryShape.of(SortKey.NONE, "stock" to Operator.LTE),
            QueryShape.of(SortKey.NONE, "stock" to Operator.GTE, "stock" to Operator.LTE),
        )
    val OPERATIONS = setOf(CrudOperation.LIST, CrudOperation.COUNT, CrudOperation.GET)

    fun resource(
        store: JooqResourceStore<Product>,
        callers: CallerLookup,
        pages: TicketProperties.Pages,
    ): CrudResource<Product> =
        CrudResource(
            QueryRules(
                SHAPES,
                FieldGrant.All,
                FieldGrant.None,
                Pagination(pages.defaultLimit, pages.maxLimit, pages.maxOffset, pages.countCap),
            ),
            ResourcePolicy(
                mapOf(Action.READ to ActionAccess.permissions(ProductPermissions.READ)),
                ScopeRule.Unrestricted,
                FieldGrant.None,
            ),
            store,
            callers,
            emptyList(),
        )

    fun mounted(resource: CrudResource<Product>) = MountedResource(PREFIX, OPERATIONS, resource)
}

@RestController
@RequestMapping(ProductDeclarations.PREFIX)
@ConditionalOnRainRole(RuntimeRole.API)
class ProductController(
    @Qualifier("productResource") private val products: CrudResource<Product>,
) : DeclaresItsOwnAccess {
    private val mounted = ProductDeclarations.mounted(products)

    @GetMapping fun list(request: HttpServletRequest): PageBody<Product> = CrudMvc.list(products, request)

    @GetMapping("/count")
    fun count(request: HttpServletRequest): CountBody = CrudMvc.count(products, request)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
        request: HttpServletRequest,
    ): Product = CrudMvc.get(products, id, request)

    override fun accessDeclarations(): List<EndpointDeclaration> = mounted.declarations()
}

@Configuration(proxyBeanMethods = false)
class ProductConfiguration {
    @Bean
    fun productStore(
        dsl: DSLContext,
        ids: IdGenerator,
    ): JooqResourceStore<Product> = JooqResourceStore(ProductFields.SCHEMA, dsl, ids, RowReader.fields(ProductFields.SCHEMA))

    @Bean
    fun productResource(
        @Qualifier("productStore") store: JooqResourceStore<Product>,
        callers: CallerLookup,
        properties: TicketProperties,
    ): CrudResource<Product> = ProductDeclarations.resource(store, callers, properties.pages)
}
