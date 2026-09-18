package com.gd.rain.crud.error

import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.ViolationOrigin
import com.gd.rain.crud.query.FieldKind
import com.gd.rain.crud.query.ResourceSchema
import com.gd.rain.crud.query.SchemaField
import com.gd.rain.crud.query.TableName
import com.gd.rain.persistence.fault.DatabaseError
import com.gd.rain.persistence.fault.DatabaseErrorSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CrudDatabaseViolationMapperTest {
    @Test
    fun `an exact declared column is resolved to the resource's public field`() {
        val mapper = CrudDatabaseViolationMapper { listOf(schema("users", "email")) }

        val violation = mapper.map(error(RainErrorCodes.REQUIRED, column = "email_address"))

        assertThat(violation!!.pointer).isEqualTo("/email")
        assertThat(violation.origin).isEqualTo(ViolationOrigin.INPUT)
    }

    @Test
    fun `resources that disagree on a public name make resolution decline instead of guess`() {
        val mapper = CrudDatabaseViolationMapper { listOf(schema("users", "email"), schema("operators", "address")) }

        assertThat(mapper.map(error(RainErrorCodes.REQUIRED, column = "email_address"))).isNull()
    }

    @Test
    fun `a matching resource without the column makes resolution decline`() {
        val mapper = CrudDatabaseViolationMapper { listOf(schema("users", "email"), schema("operators", "name", "display_name")) }

        assertThat(mapper.map(error(RainErrorCodes.REQUIRED, column = "email_address"))).isNull()
    }

    @Test
    fun `constraint-only provenance is not inferred from its spelling`() {
        val mapper = CrudDatabaseViolationMapper { listOf(schema("users", "email")) }

        assertThat(mapper.map(error(RainErrorCodes.UNIQUE, column = null))).isNull()
    }

    @Test
    fun `a stored-state collision keeps its origin after path resolution`() {
        val mapper = CrudDatabaseViolationMapper { listOf(schema("users", "email")) }

        assertThat(mapper.map(error(RainErrorCodes.UNIQUE, column = "email_address"))!!.origin).isEqualTo(ViolationOrigin.STATE)
    }

    private fun error(
        code: ErrorCode,
        column: String?,
    ): DatabaseError =
        DatabaseError(
            sqlState = if (code == RainErrorCodes.UNIQUE) "23505" else "23502",
            kind = if (code == RainErrorCodes.UNIQUE) FaultKind.CONFLICT else FaultKind.VALIDATION,
            defaultCode = code,
            source = DatabaseErrorSource("public", "users", column, "users_email_constraint"),
        )

    private fun schema(
        name: String,
        publicField: String,
        column: String = "email_address",
    ): ResourceSchema =
        ResourceSchema(
            name = name,
            table = TableName("public", "users"),
            id = SchemaField("id", "id", FieldKind.UUID, nullable = false),
            fields = listOf(SchemaField(publicField, column, FieldKind.TEXT, nullable = false)),
            version = null,
        )
}
