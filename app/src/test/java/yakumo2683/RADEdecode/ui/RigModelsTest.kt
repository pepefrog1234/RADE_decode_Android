package yakumo2683.RADEdecode.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RigModelsTest {
    /**
     * The Rig tab saves the selected model as its position in [rigModels]
     * ("rig_index"). These are the positions of the list as released with
     * v1.6.33: a model inserted or removed before the end would silently give
     * every user who had selected a later one a different rig.
     */
    private val releasedOrder = listOf(
        1, 2, 4, 7, 1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010,
        1011, 1012, 1013, 1014, 1015, 1016, 1017, 1018, 1019, 1020, 1021, 1022, 1023, 1024,
        1025, 1026, 1027, 1028, 1029, 1030, 1031, 1032, 1033, 1034, 1035, 1036, 1037, 1039,
        1040, 1041, 1042, 1043, 1044, 1046, 1047, 1049, 2001, 2002, 2003, 2004, 2005, 2006,
        2007, 2009, 2010, 2011, 2012, 2013, 2014, 2015, 2016, 2022, 2024, 2025, 2028, 2031,
        2037, 2039, 2041, 2021, 2029, 2043, 2044, 2045, 2047, 2038, 2036, 2048, 2040, 2049,
        2050, 2051, 2052, 3001, 3003, 3004, 3008, 3009, 3010, 3011, 3012, 3013, 3014, 3015,
        3016, 3019, 3020, 3023, 3024, 3026, 3027, 3028, 3029, 3030, 3031, 3035, 3039, 3044,
        3046, 3047, 3055, 3056, 3057, 3060, 3061, 3062, 3063, 3067, 3068, 3070, 3073, 3075,
        3078, 3081, 3085, 3087, 3088, 3089, 3076, 30001, 30002, 30003, 30004, 4001, 4002, 4003,
        4004, 5001, 5003, 5004, 5013, 5016, 6001, 6002, 6006, 6007, 16001, 16002, 16003, 16008,
        16011, 16013, 17001, 17002, 9001, 9002, 9003, 33001, 32001, 32002, 32003, 34001, 34002, 29001
    )

    @Test fun savedSelectionsStillPointAtTheSameModel() {
        assertEquals(releasedOrder, rigModels.take(releasedOrder.size).map { it.id })
    }

    @Test fun everyModelIsListedOnce() {
        val repeated = rigModels.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<Int>(), repeated)
    }
}
