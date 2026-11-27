package com.maxrave.kotlinytmusicscraper

import android.content.res.Resources
import java.util.Locale

actual fun getCountry(): String = Resources.getSystem().configuration.locales[0].country
actual fun getLanguage(): String = Locale.getDefault().language