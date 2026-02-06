package org.example.project.junit.xmlreport
import java.text.ChoiceFormat
import java.text.DateFormat
import java.text.MessageFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */


/**
 * NOTE: This class is from Apache Ant 1.7.1
 * Helper methods to deal with date/time formatting with a specific
 * defined format ([ISO8601](http://www.w3.org/TR/NOTE-datetime))
 * or a plurialization correct elapsed time in minutes and seconds.
 *
 * @since Ant 1.5
 */
internal object DateUtils {
    private const val ONE_SECOND = 1000
    private const val ONE_MINUTE = 60
    private const val ONE_HOUR = 60
    private const val TEN = 10

    /**
     * ISO8601-like pattern for date-time. It does not support timezone.
     * <tt>yyyy-MM-ddTHH:mm:ss</tt>
     */
    const val ISO8601_DATETIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss"

    /**
     * ISO8601-like pattern for date. <tt>yyyy-MM-dd</tt>
     */
    const val ISO8601_DATE_PATTERN = "yyyy-MM-dd"

    /**
     * ISO8601-like pattern for time.  <tt>HH:mm:ss</tt>
     */
    const val ISO8601_TIME_PATTERN = "HH:mm:ss"

    /**
     * Format used for SMTP (and probably other) Date headers.
     */
    val DATE_HEADER_FORMAT: DateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss ", Locale.US)

    // code from Magesh moved from DefaultLogger and slightly modified
    private val MINUTE_SECONDS = MessageFormat("{0}{1}")
    private val LIMITS = doubleArrayOf(0.0, 1.0, 2.0)
    private val MINUTES_PART = arrayOf("", "1 minute ", "{0,number} minutes ")
    private val SECONDS_PART = arrayOf("0 seconds", "1 second", "{1,number} seconds")
    private val MINUTES_FORMAT = ChoiceFormat(LIMITS, MINUTES_PART)
    private val SECONDS_FORMAT = ChoiceFormat(LIMITS, SECONDS_PART)

    init {
        MINUTE_SECONDS.setFormat(0, MINUTES_FORMAT)
        MINUTE_SECONDS.setFormat(1, SECONDS_FORMAT)
    }

    /**
     * Format a date/time into a specific pattern.
     * @param date the date to format expressed in milliseconds.
     * @param pattern the pattern to use to format the date.
     * @return the formatted date.
     */
    fun format(date: Long, pattern: String?): String {
        return format(Date(date), pattern)
    }

    /**
     * Format a date/time into a specific pattern.
     * @param date the date to format expressed in milliseconds.
     * @param pattern the pattern to use to format the date.
     * @return the formatted date.
     */
    fun format(date: Date?, pattern: String?): String {
        val df = createDateFormat(pattern)
        return df.format(date)
    }

    /**
     * Format an elapsed time into a plurialization correct string.
     * It is limited only to report elapsed time in minutes and
     * seconds and has the following behavior.
     *
     *  * minutes are not displayed when 0. (ie: "45 seconds")
     *  * seconds are always displayed in plural form (ie "0 seconds" or
     * "10 seconds") except for 1 (ie "1 second")
     *
     * @param millis the elapsed time to report in milliseconds.
     * @return the formatted text in minutes/seconds.
     */
    fun formatElapsedTime(millis: Long): String {
        val seconds = millis / ONE_SECOND
        val minutes = seconds / ONE_MINUTE
        val args = arrayOf<Any>(minutes, seconds % ONE_MINUTE)
        return MINUTE_SECONDS.format(args)
    }

    /**
     * return a lenient date format set to GMT time zone.
     * @param pattern the pattern used for date/time formatting.
     * @return the configured format for this pattern.
     */
    private fun createDateFormat(pattern: String?): DateFormat {
        val sdf = SimpleDateFormat(pattern)
        val gmt = TimeZone.getTimeZone("GMT")
        sdf.timeZone = gmt
        sdf.isLenient = true
        return sdf
    }

    /**
     * Calculate the phase of the moon for a given date.
     *
     *
     * Code heavily influenced by hacklib.c in [Nethack](http://www.nethack.org/)
     *
     *
     * The Algorithm:
     *
     * <pre>
     * moon period = 29.53058 days ~= 30, year = 365.2422 days
     *
     * days moon phase advances on first day of year compared to preceding year
     * = 365.2422 - 12*29.53058 ~= 11
     *
     * years in Metonic cycle (time until same phases fall on the same days of
     * the month) = 18.6 ~= 19
     *
     * moon phase on first day of year (epact) ~= (11*(year%19) + 18) % 30
     * (18 as initial condition for 1900)
     *
     * current phase in days = first day phase + days elapsed in year
     *
     * 6 moons ~= 177 days
     * 177 ~= 8 reported phases * 22
     * + 11/22 for rounding
    </pre> *
     *
     * @param cal the calander.
     *
     * @return The phase of the moon as a number between 0 and 7 with
     * 0 meaning new moon and 4 meaning full moon.
     *
     * @since 1.2, Ant 1.5
     */
    fun getPhaseOfMoon(cal: Calendar): Int {
        // CheckStyle:MagicNumber OFF
        val dayOfTheYear = cal[Calendar.DAY_OF_YEAR]
        val yearInMetonicCycle = (cal[Calendar.YEAR] - 1900) % 19 + 1
        var epact = (11 * yearInMetonicCycle + 18) % 30
        if (epact == 25 && yearInMetonicCycle > 11 || epact == 24) {
            epact++
        }
        return ((dayOfTheYear + epact) * 6 + 11) % 177 / 22 and 7
        // CheckStyle:MagicNumber ON
    }

    val dateForHeader: String
        /**
         * Returns the current Date in a format suitable for a SMTP date
         * header.
         * @return the current date.
         * @since Ant 1.5.2
         */
        get() {
            val cal = Calendar.getInstance()
            val tz = cal.timeZone
            var offset = tz.getOffset(
                cal[Calendar.ERA],
                cal[Calendar.YEAR],
                cal[Calendar.MONTH],
                cal[Calendar.DAY_OF_MONTH],
                cal[Calendar.DAY_OF_WEEK],
                cal[Calendar.MILLISECOND]
            )
            val tzMarker = StringBuffer(if (offset < 0) "-" else "+")
            offset = abs(offset.toDouble()).toInt()
            val hours = offset / (ONE_HOUR * ONE_MINUTE * ONE_SECOND)
            val minutes = offset / (ONE_MINUTE * ONE_SECOND) - ONE_HOUR * hours
            if (hours < TEN) {
                tzMarker.append("0")
            }
            tzMarker.append(hours)
            if (minutes < TEN) {
                tzMarker.append("0")
            }
            tzMarker.append(minutes)
            return DATE_HEADER_FORMAT.format(cal.time) + tzMarker.toString()
        }

    /**
     * Parse a string as a datetime using the ISO8601_DATETIME format which is
     * `yyyy-MM-dd'T'HH:mm:ss`
     *
     * @param datestr string to be parsed
     *
     * @return a java.util.Date object as parsed by the format.
     * @exception ParseException if the supplied string cannot be parsed by
     * this pattern.
     * @since Ant 1.6
     */
    @Throws(ParseException::class)
    fun parseIso8601DateTime(datestr: String?): Date {
        return SimpleDateFormat(ISO8601_DATETIME_PATTERN).parse(datestr)
    }

    /**
     * Parse a string as a date using the ISO8601_DATE format which is
     * `yyyy-MM-dd`
     *
     * @param datestr string to be parsed
     *
     * @return a java.util.Date object as parsed by the format.
     * @exception ParseException if the supplied string cannot be parsed by
     * this pattern.
     * @since Ant 1.6
     */
    @Throws(ParseException::class)
    fun parseIso8601Date(datestr: String?): Date {
        return SimpleDateFormat(ISO8601_DATE_PATTERN).parse(datestr)
    }

    /**
     * Parse a string as a date using the either the ISO8601_DATETIME
     * or ISO8601_DATE formats.
     *
     * @param datestr string to be parsed
     *
     * @return a java.util.Date object as parsed by the formats.
     * @exception ParseException if the supplied string cannot be parsed by
     * either of these patterns.
     * @since Ant 1.6
     */
    @Throws(ParseException::class)
    fun parseIso8601DateTimeOrDate(datestr: String?): Date {
        return try {
            parseIso8601DateTime(datestr)
        } catch (px: ParseException) {
            parseIso8601Date(datestr)
        }
    }
}