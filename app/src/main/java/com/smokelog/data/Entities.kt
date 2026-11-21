package com.smokelog.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** All temperatures are stored in °F. */

@Serializable
enum class FatCap(val label: String) { UP("Fat cap up"), DOWN("Fat cap down"), NA("N/A") }

@Serializable
enum class Placement(val label: String) {
    TOP_RACK("Top rack"), MIDDLE_RACK("Middle rack"), BOTTOM_RACK("Bottom rack"),
    LEFT("Left side"), CENTER("Center"), RIGHT("Right side"),
    NEAR_FIREPOT("Near fire pot / hot zone"), NEAR_CHIMNEY("Near chimney / exhaust"),
    HANGING("Hanging"), OTHER("Other")
}

@Serializable
enum class WrapMethod(val label: String) {
    NONE("Not wrapped"), BUTCHER_PAPER("Butcher paper"), FOIL("Foil"),
    FOIL_BOAT("Foil boat"), PAN_COVERED("Covered pan"), PARCHMENT("Parchment"), OTHER("Other")
}

@Serializable
enum class RestLocation(val label: String) {
    COOLER("Cooler"), OVEN("Oven (warm/hold)"), COUNTER("Counter / tented"),
    WARMING_DRAWER("Warming drawer"), SMOKER_OFF("In smoker, off"), OTHER("Other")
}

@Serializable
enum class EventType(val label: String) {
    NOTE("Note"), SPRITZ("Spritz / mop"), TEMP_CHANGE("Smoker temp change"),
    WOOD_ADDED("Wood / pellets added"), FLIPPED("Flipped / rotated"),
    STALL("Stall observed"), WRAPPED("Wrapped"), PULLED("Taken off"), REST_START("Rest started"),
    REST_END("Rest ended / sliced")
}

@Serializable
@Entity(tableName = "cooks")
data class Cook(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val meat: String = "",            // e.g. "Beef brisket", "Pork butt"
    val cut: String = "",             // e.g. "Packer, prime"
    val weightLb: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),

    // Smoker setup
    val smoker: String = "",          // e.g. "Traeger Ironwood 650"
    val fuel: String = "",            // pellets / wood blend
    val smokerTempF: Int? = null,     // target pit temp
    val placement: Placement? = null,
    val placementNotes: String = "",
    val fatCap: FatCap? = null,
    val waterPan: Boolean? = null,
    val waterPanNotes: String = "",

    // Preparation
    val trimNotes: String = "",
    val binder: String = "",
    val seasoning: String = "",
    val seasonedHoursBefore: Double? = null,
    val dryBrine: String = "",
    val dryBrineHours: Double? = null,
    val wetBrine: String = "",
    val wetBrineHours: Double? = null,
    val injection: String = "",
    val injectionHoursBefore: Double? = null,
    val prepNotes: String = "",

    // Timeline (epoch millis)
    val startTime: Long? = null,      // meat on
    val startInternalF: Int? = null,
    val wrapTime: Long? = null,
    val wrapInternalF: Int? = null,
    val wrapMethod: WrapMethod? = null,
    val wrapNotes: String = "",       // tallow, butter, spritz before wrap...
    val offTime: Long? = null,        // taken off smoker
    val offInternalF: Int? = null,
    val restLocation: RestLocation? = null,
    val restEndTime: Long? = null,
    val restMinutes: Int? = null,     // manual override when restEndTime not recorded
    val restNotes: String = "",

    // Outcome
    val rating: Int? = null,          // 1-5
    val resultNotes: String = "",
    val aiAnalysis: String = "",
    /** Shown on the next cook of the same meat. */
    @ColumnInfo(defaultValue = "") val nextCookNotes: String = "",

    // Live data
    val traegerThing: String? = null, // grill id used for live readings
) {
    val cookMinutes: Long? get() = if (startTime != null && offTime != null) (offTime - startTime) / 60_000 else null
    val wrapElapsedMinutes: Long? get() = if (startTime != null && wrapTime != null) (wrapTime - startTime) / 60_000 else null
    val effectiveRestMinutes: Int?
        get() = restMinutes ?: if (offTime != null && restEndTime != null) ((restEndTime - offTime) / 60_000).toInt() else null
    val displayTitle: String get() = title.ifBlank { listOf(meat, cut).filter { it.isNotBlank() }.joinToString(" – ").ifBlank { "Untitled cook" } }
}

@Serializable
@Entity(
    tableName = "readings",
    foreignKeys = [ForeignKey(entity = Cook::class, parentColumns = ["id"], childColumns = ["cookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cookId")],
)
data class TempReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cookId: Long,
    val time: Long,
    val grillF: Double? = null,
    val grillSetF: Double? = null,
    val probeF: Double? = null,
    val probe2F: Double? = null,
    val probeTargetF: Double? = null,
    val source: String = "manual",   // manual | traeger | demo
)

@Serializable
@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(entity = Cook::class, parentColumns = ["id"], childColumns = ["cookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cookId")],
)
data class CookEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cookId: Long,
    val time: Long,
    val type: EventType,
    val note: String = "",
)

/** A picture attached to a cook. The JPEG lives in filesDir/photos/[fileName]. */
@Serializable
@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(entity = Cook::class, parentColumns = ["id"], childColumns = ["cookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cookId")],
)
data class CookPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cookId: Long,
    val time: Long,
    val fileName: String,
    val caption: String = "",
)
