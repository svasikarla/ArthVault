package com.arthvault.data.local

import com.arthvault.data.local.entity.CategoryEntity

/**
 * The rules about creating and removing a user's own categories.
 *
 * Kept as pure decisions, separate from the IO that carries them out, for the same
 * reason [AdjustmentFolder] is: the interesting part is what is allowed, and it should
 * be testable without opening an encrypted database.
 *
 * Two of these guards exist because the storage would otherwise fail quietly.
 * `categories.name` is the primary key and `insertCategory` is a REPLACE, so creating
 * "Grocery" a second time silently overwrites the built-in row and flips it to
 * `isCustom` — after which a wipe would delete it as user data. And a differently
 * cased "grocery" is a *different* primary key, so it inserts cleanly and then splits
 * the analytics breakdown into two rows the user reads as one category.
 */
object CategoryEditor {

    /**
     * A category name appears in a single-line filter chip, in a feed row's subtitle
     * and in a donut legend. The longest built-in is "Entertainment & Subs" at 20;
     * this leaves headroom without letting one name reflow three screens.
     */
    const val MAX_NAME_LENGTH = 28

    private val COLLAPSIBLE_WHITESPACE = Regex("\\s+")

    fun validateNew(rawName: String, existing: List<String>): AddCategoryOutcome {
        // Collapsed, so "Pet  Care" and "Pet Care" cannot become two categories that
        // are impossible to tell apart in a chip.
        val name = rawName.trim().replace(COLLAPSIBLE_WHITESPACE, " ")

        return when {
            name.isBlank() -> AddCategoryOutcome.Blank
            name.length > MAX_NAME_LENGTH -> AddCategoryOutcome.TooLong(MAX_NAME_LENGTH)
            else -> {
                val clash = existing.firstOrNull { it.equals(name, ignoreCase = true) }
                if (clash != null) AddCategoryOutcome.AlreadyExists(clash)
                else AddCategoryOutcome.Added(name)
            }
        }
    }

    /**
     * Whether a category can be removed.
     *
     * Built-in categories are never removable: they are system data that a wipe
     * re-seeds, so "deleting" one would only make it reappear on the next restore.
     *
     * A category still on transactions is refused rather than cascaded. Deleting the
     * row would leave those transactions labelled with a category that no longer
     * exists — the label survives on the row, the filter chip does not, and the
     * analytics breakdown groups by a name nothing in the UI can now select. T3.3
     * forbids the alternative outright: rewriting those transactions to something else
     * would be editing what the bank said.
     *
     * Merchant rules count too. A rule pointing at a deleted category would simply
     * re-create the orphaned label on the next scan.
     */
    fun canDelete(
        name: String,
        categories: List<CategoryEntity>,
        transactionsUsing: Int,
        rulesUsing: Int,
    ): DeleteCategoryOutcome {
        val row = categories.firstOrNull { it.name == name }
            ?: return DeleteCategoryOutcome.NotFound
        if (!row.isCustom) return DeleteCategoryOutcome.BuiltIn
        if (transactionsUsing > 0 || rulesUsing > 0) {
            return DeleteCategoryOutcome.StillInUse(transactionsUsing, rulesUsing)
        }
        return DeleteCategoryOutcome.Deleted
    }
}

/** The result of trying to create a category. [Added] carries the normalised name. */
sealed interface AddCategoryOutcome {
    data class Added(val name: String) : AddCategoryOutcome
    data object Blank : AddCategoryOutcome
    data class TooLong(val limit: Int) : AddCategoryOutcome
    data class AlreadyExists(val existing: String) : AddCategoryOutcome
}

/** The result of trying to remove one. */
sealed interface DeleteCategoryOutcome {
    data object Deleted : DeleteCategoryOutcome
    data object BuiltIn : DeleteCategoryOutcome
    data object NotFound : DeleteCategoryOutcome
    data class StillInUse(val transactions: Int, val rules: Int) : DeleteCategoryOutcome
}
