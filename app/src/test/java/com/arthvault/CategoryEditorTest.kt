package com.arthvault

import com.arthvault.data.local.AddCategoryOutcome
import com.arthvault.data.local.CategoryEditor
import com.arthvault.data.local.DefaultSeedData
import com.arthvault.data.local.DeleteCategoryOutcome
import com.arthvault.data.local.entity.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules about the user's own categories.
 *
 * Two of these cases are the reason this class exists rather than a bare
 * `insertCategory` call. `categories.name` is the primary key and the insert is a
 * REPLACE, so an unguarded duplicate does not fail — it overwrites, and a built-in
 * category quietly becomes user data that the next wipe deletes. A case variant is
 * worse, because it is a *different* key: it inserts cleanly and then splits the
 * analytics breakdown into two rows the user reads as one category.
 */
class CategoryEditorTest {

    private val existing = DefaultSeedData.categories.map { it.name }

    // --- creating ----------------------------------------------------------

    @Test
    fun `a new name is accepted and comes back normalised`() {
        val outcome = CategoryEditor.validateNew("  Pet   Care ", existing)
        assertEquals(AddCategoryOutcome.Added("Pet Care"), outcome)
    }

    @Test
    fun `an exact duplicate is refused rather than silently replacing the built-in`() {
        val outcome = CategoryEditor.validateNew("Grocery", existing)
        assertEquals(AddCategoryOutcome.AlreadyExists("Grocery"), outcome)
    }

    @Test
    fun `a duplicate in different case is refused, and names the one that exists`() {
        // This is the case a primary-key constraint does not catch: "grocery" and
        // "Grocery" are two rows, two chips, and two slices of the same spending.
        val outcome = CategoryEditor.validateNew("  gRoCeRy  ", existing)
        assertEquals(AddCategoryOutcome.AlreadyExists("Grocery"), outcome)
    }

    @Test
    fun `whitespace-only names are refused`() {
        assertEquals(AddCategoryOutcome.Blank, CategoryEditor.validateNew("", existing))
        assertEquals(AddCategoryOutcome.Blank, CategoryEditor.validateNew("   ", existing))
        assertEquals(AddCategoryOutcome.Blank, CategoryEditor.validateNew("\n\t", existing))
    }

    @Test
    fun `a name too long for a chip is refused`() {
        val long = "a".repeat(CategoryEditor.MAX_NAME_LENGTH + 1)
        assertEquals(
            AddCategoryOutcome.TooLong(CategoryEditor.MAX_NAME_LENGTH),
            CategoryEditor.validateNew(long, existing)
        )
        // The boundary itself is allowed.
        val atLimit = "a".repeat(CategoryEditor.MAX_NAME_LENGTH)
        assertEquals(AddCategoryOutcome.Added(atLimit), CategoryEditor.validateNew(atLimit, existing))
    }

    @Test
    fun `every built-in category fits the limit it enforces`() {
        // A limit that the app's own seed data violates would refuse a name the user
        // can already see in the list.
        DefaultSeedData.categories.forEach { category ->
            assertTrue(
                "'${category.name}' is ${category.name.length} chars, over the limit",
                category.name.length <= CategoryEditor.MAX_NAME_LENGTH
            )
        }
    }

    // --- removing ----------------------------------------------------------

    private val categories = DefaultSeedData.categories +
        CategoryEntity("Pet Care", "Category", "#607D8B", isCustom = true)

    @Test
    fun `an unused custom category can be removed`() {
        assertEquals(
            DeleteCategoryOutcome.Deleted,
            CategoryEditor.canDelete("Pet Care", categories, transactionsUsing = 0, rulesUsing = 0)
        )
    }

    @Test
    fun `a built-in category is never removable`() {
        // Removing it would only make it reappear: a wipe re-seeds the built-in set.
        assertEquals(
            DeleteCategoryOutcome.BuiltIn,
            CategoryEditor.canDelete("Grocery", categories, transactionsUsing = 0, rulesUsing = 0)
        )
    }

    @Test
    fun `a category still on transactions is refused, not cascaded`() {
        // T3.3 forbids the alternative: rewriting those transactions to another
        // category would be editing what the bank said. Leaving them pointed at a
        // deleted category is worse — the label survives on the row while the filter
        // chip that could find it does not.
        assertEquals(
            DeleteCategoryOutcome.StillInUse(transactions = 3, rules = 0),
            CategoryEditor.canDelete("Pet Care", categories, transactionsUsing = 3, rulesUsing = 0)
        )
    }

    @Test
    fun `a category only a merchant rule points at is still refused`() {
        // Nothing uses it today, but the rule would re-create the orphaned label on
        // the very next scan.
        assertEquals(
            DeleteCategoryOutcome.StillInUse(transactions = 0, rules = 1),
            CategoryEditor.canDelete("Pet Care", categories, transactionsUsing = 0, rulesUsing = 1)
        )
    }

    @Test
    fun `removing something that is not there is reported, not assumed`() {
        assertEquals(
            DeleteCategoryOutcome.NotFound,
            CategoryEditor.canDelete("Aquarium", categories, transactionsUsing = 0, rulesUsing = 0)
        )
    }
}
