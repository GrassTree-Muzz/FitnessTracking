package com.example.peakmildeffort

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/** Reads what Garmin Connect has shared with Health Connect and packs it into compact JSON for the page. */
class HealthReader(private val client: HealthConnectClient) {

    private val garmin = setOf(DataOrigin(GARMIN_PACKAGE))

    fun requestedPermissions(): Set<String> {
        val permissions = RECORD_TYPES.map { HealthPermission.getReadPermission(it) }.toMutableSet()
        if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        ) {
            permissions += HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
        }
        return permissions
    }

    suspend fun status(): JSONObject {
        val granted = client.permissionController.getGrantedPermissions()
        val wanted = requestedPermissions()
        return JSONObject()
            .put("type", "status")
            .put("available", true)
            .put("connected", RECORD_TYPES.any { HealthPermission.getReadPermission(it) in granted })
            .put("complete", granted.containsAll(wanted))
    }

    suspend fun sync(activityDays: Long, healthDays: Long, known: Map<String, Long>): JSONObject {
        val granted = client.permissionController.getGrantedPermissions()
        val can = { type: KClass<out Record> -> HealthPermission.getReadPermission(type) in granted }
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val today = LocalDate.now(zone)
        val healthFirstDay = today.minusDays(healthDays - 1)
        val healthStart = healthFirstDay.atStartOfDay(zone).toInstant()
        val activityStart = today.minusDays(activityDays - 1).atStartOfDay(zone).toInstant()

        val activities = if (can(ExerciseSessionRecord::class)) {
            activities(TimeRangeFilter.between(activityStart, now), can, known)
        } else {
            JSONArray()
        }

        val dayMetrics = buildSet<AggregateMetric<*>> {
            if (can(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (can(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
            if (can(FloorsClimbedRecord::class)) add(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL)
            if (can(TotalCaloriesBurnedRecord::class)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (can(HeartRateRecord::class)) addAll(listOf(HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX))
        }
        val days = JSONArray()
        if (dayMetrics.isNotEmpty()) {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = dayMetrics,
                    timeRangeFilter = TimeRangeFilter.between(healthFirstDay.atStartOfDay(), today.plusDays(1).atStartOfDay()),
                    timeRangeSlicer = Period.ofDays(1),
                    dataOriginFilter = garmin,
                )
            ).forEach { day ->
                val result = day.result
                days.put(
                    JSONObject()
                        .put("date", day.startTime.toLocalDate().toString())
                        .putOpt("steps", result[StepsRecord.COUNT_TOTAL])
                        .putOpt("distanceM", result[DistanceRecord.DISTANCE_TOTAL]?.inMeters)
                        .putOpt("floors", result[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL])
                        .putOpt("kcal", result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories)
                        .putOpt("hrMin", result[HeartRateRecord.BPM_MIN])
                        .putOpt("hrAvg", result[HeartRateRecord.BPM_AVG])
                        .putOpt("hrMax", result[HeartRateRecord.BPM_MAX])
                )
            }
        }

        // 30-minute heart-rate averages drive the daily chart and the overnight resting estimate.
        val hr30 = JSONArray()
        if (can(HeartRateRecord::class)) {
            var chunkStart = healthStart
            while (chunkStart < now) {
                val chunkEnd = minOf(chunkStart.plus(Duration.ofDays(7)), now)
                client.aggregateGroupByDuration(
                    AggregateGroupByDurationRequest(
                        metrics = setOf(HeartRateRecord.BPM_AVG),
                        timeRangeFilter = TimeRangeFilter.between(chunkStart, chunkEnd),
                        timeRangeSlicer = Duration.ofMinutes(30),
                        dataOriginFilter = garmin,
                    )
                ).forEach { bucket ->
                    bucket.result[HeartRateRecord.BPM_AVG]?.let { hr30.put(JSONArray().put(bucket.startTime.toEpochMilli()).put(it)) }
                }
                chunkStart = chunkEnd
            }
        }

        val sleep = JSONArray()
        if (can(SleepSessionRecord::class)) {
            readAll(SleepSessionRecord::class, TimeRangeFilter.between(healthStart.minus(Duration.ofDays(1)), now), garmin).forEach { session ->
                val stages = JSONArray()
                session.stages.forEach { stages.put(JSONArray().put(it.startTime.toEpochMilli()).put(it.endTime.toEpochMilli()).put(it.stage)) }
                sleep.put(
                    JSONObject()
                        .put("start", session.startTime.toEpochMilli())
                        .put("end", session.endTime.toEpochMilli())
                        .put("stages", stages)
                )
            }
        }

        val bodyRange = TimeRangeFilter.between(activityStart, now)
        val weight = JSONArray()
        if (can(WeightRecord::class)) {
            readAll(WeightRecord::class, bodyRange, garmin).forEach { weight.put(JSONArray().put(it.time.toEpochMilli()).put(it.weight.inKilograms)) }
        }
        val bodyFat = JSONArray()
        if (can(BodyFatRecord::class)) {
            readAll(BodyFatRecord::class, bodyRange, garmin).forEach { bodyFat.put(JSONArray().put(it.time.toEpochMilli()).put(it.percentage.value)) }
        }

        return JSONObject()
            .put("type", "data")
            .put("generatedAt", now.toEpochMilli())
            .put("history", HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted)
            .put("activities", activities)
            .put("days", days)
            .put("hr30", hr30)
            .put("sleep", sleep)
            .put("weight", weight)
            .put("bodyFat", bodyFat)
    }

    suspend fun activityDetail(id: String): JSONObject {
        val granted = client.permissionController.getGrantedPermissions()
        val session = client.readRecord(ExerciseSessionRecord::class, id).record
        val range = TimeRangeFilter.between(session.startTime, session.endTime)
        val origin = setOf(session.metadata.dataOrigin)
        val inRun = { time: Instant -> !time.isBefore(session.startTime) && !time.isAfter(session.endTime) }

        val hr = JSONArray()
        if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted) {
            readAll(HeartRateRecord::class, range, origin)
                .flatMap { it.samples }
                .filter { inRun(it.time) }
                .sortedBy { it.time }
                .forEach { hr.put(JSONArray().put(it.time.toEpochMilli()).put(it.beatsPerMinute)) }
        }
        val speed = JSONArray()
        if (HealthPermission.getReadPermission(SpeedRecord::class) in granted) {
            readAll(SpeedRecord::class, range, origin)
                .flatMap { it.samples }
                .filter { inRun(it.time) }
                .sortedBy { it.time }
                .forEach { speed.put(JSONArray().put(it.time.toEpochMilli()).put(it.speed.inMetersPerSecond)) }
        }
        return JSONObject().put("type", "activity").put("id", id).put("hr", hr).put("speed", speed)
    }

    private suspend fun activities(range: TimeRangeFilter, can: (KClass<out Record>) -> Boolean, known: Map<String, Long>): JSONArray {
        val metrics = buildSet<AggregateMetric<*>> {
            add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
            if (can(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
            if (can(ElevationGainedRecord::class)) add(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)
            if (can(ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (can(TotalCaloriesBurnedRecord::class)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (can(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (can(HeartRateRecord::class)) addAll(listOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX))
        }
        val activities = JSONArray()
        readAll(ExerciseSessionRecord::class, range, garmin).forEach { session ->
            val id = session.metadata.id
            val modified = session.metadata.lastModifiedTime.toEpochMilli()
            // The page already holds this summary, so skip the per-activity aggregate call.
            if (known[id] == modified) {
                activities.put(JSONObject().put("id", id).put("modified", modified).put("same", true))
                return@forEach
            }
            val result = client.aggregate(
                AggregateRequest(
                    metrics = metrics,
                    timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime),
                    dataOriginFilter = setOf(session.metadata.dataOrigin),
                )
            )
            activities.put(
                JSONObject()
                    .put("id", id)
                    .put("modified", modified)
                    .put("type", session.exerciseType)
                    .put("start", session.startTime.toEpochMilli())
                    .put("end", session.endTime.toEpochMilli())
                    .putOpt("title", session.title)
                    .putOpt("durationMs", result[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMillis())
                    .putOpt("distanceM", result[DistanceRecord.DISTANCE_TOTAL]?.inMeters)
                    .putOpt("elevationM", result[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters)
                    .putOpt("activeKcal", result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories)
                    .putOpt("totalKcal", result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories)
                    .putOpt("steps", result[StepsRecord.COUNT_TOTAL])
                    .putOpt("avgHr", result[HeartRateRecord.BPM_AVG])
                    .putOpt("maxHr", result[HeartRateRecord.BPM_MAX])
            )
        }
        return activities
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter, origins: Set<DataOrigin>): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(recordType = type, timeRangeFilter = range, dataOriginFilter = origins, pageToken = pageToken)
            )
            records += response.records
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return records
    }

    companion object {
        const val GARMIN_PACKAGE = "com.garmin.android.apps.connectmobile"

        private val RECORD_TYPES: List<KClass<out Record>> = listOf(
            ExerciseSessionRecord::class,
            DistanceRecord::class,
            HeartRateRecord::class,
            SpeedRecord::class,
            StepsRecord::class,
            ActiveCaloriesBurnedRecord::class,
            TotalCaloriesBurnedRecord::class,
            ElevationGainedRecord::class,
            FloorsClimbedRecord::class,
            SleepSessionRecord::class,
            WeightRecord::class,
            BodyFatRecord::class,
        )
    }
}
