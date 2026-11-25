package com.velocity.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.velocity.app.resources.Res
import com.velocity.app.resources.dm_sans_bold
import com.velocity.app.resources.dm_sans_medium
import com.velocity.app.resources.dm_sans_regular
import com.velocity.app.resources.outfit_bold
import com.velocity.app.resources.outfit_medium
import com.velocity.app.resources.outfit_semibold
import org.jetbrains.compose.resources.Font

@Composable
fun rememberVelocityTypography(): Typography {
    val outfitFamily = FontFamily(
        Font(Res.font.outfit_medium, FontWeight.Medium),
        Font(Res.font.outfit_semibold, FontWeight.SemiBold),
        Font(Res.font.outfit_bold, FontWeight.Bold),
    )
    val dmSansFamily = FontFamily(
        Font(Res.font.dm_sans_regular, FontWeight.Normal),
        Font(Res.font.dm_sans_medium, FontWeight.Medium),
        Font(Res.font.dm_sans_bold, FontWeight.Bold),
    )

    return Typography(
        displayLarge = TextStyle(
            fontFamily = outfitFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 42.sp,
            lineHeight = 48.sp,
            letterSpacing = (-0.8).sp,
            fontFeatureSettings = "tnum",
        ),
        displayMedium = TextStyle(
            fontFamily = outfitFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            lineHeight = 38.sp,
            letterSpacing = (-0.4).sp,
            fontFeatureSettings = "tnum",
        ),
        headlineLarge = TextStyle(
            fontFamily = outfitFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.3).sp,
        ),
        headlineMedium = TextStyle(
            fontFamily = outfitFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 21.sp,
            lineHeight = 27.sp,
            letterSpacing = (-0.2).sp,
        ),
        titleLarge = TextStyle(
            fontFamily = outfitFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            lineHeight = 24.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = dmSansFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            lineHeight = 21.sp,
        ),
        bodyLarge = TextStyle(
            fontFamily = dmSansFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = dmSansFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = dmSansFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.1.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = dmSansFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = dmSansFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.2.sp,
        ),
    )
}
