package com.maxrave.kotlinytmusicscraper

import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

actual fun getCountry(): String = NSLocale.currentLocale.countryCode ?: "US"
actual fun getLanguage(): String = NSLocale.currentLocale.languageCode