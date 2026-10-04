package org.fossify.calendar.interfaces

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.calendar.helpers.MATCH_EVENT_ID
import org.fossify.calendar.models.ContextualRule

@Dao
interface ContextualRulesDao {
    @Query("SELECT * FROM contextual_rules ORDER BY id ASC")
    fun getRules(): List<ContextualRule>

    @Query("SELECT * FROM contextual_rules WHERE enabled = 1")
    fun getEnabledRules(): List<ContextualRule>

    @Query("SELECT * FROM contextual_rules WHERE id = :id")
    fun getRuleWithId(id: Long): ContextualRule?

    // used to find (and undo) an existing "mark as contextual" on a single event
    @Query("SELECT * FROM contextual_rules WHERE match_type = $MATCH_EVENT_ID AND event_id = :eventId")
    fun getRulesForEventId(eventId: Long): List<ContextualRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(rule: ContextualRule): Long

    @Delete
    fun deleteRules(rules: List<ContextualRule>)
}
