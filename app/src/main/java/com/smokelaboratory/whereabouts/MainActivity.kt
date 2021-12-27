package com.smokelaboratory.whereabouts

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        lifecycleScope.launch {
            try {
                val location = WhereAbouts(this@MainActivity).fetchLocation()
                findViewById<TextView>(R.id.tv_location).text = "${location.latitude}, ${location.longitude}"
            } catch (e: WhereAbouts.LocationPermissionException) {
                e.printStackTrace()
            } catch (e: WhereAbouts.AccuracyPermissionException) {
                e.printStackTrace()
            } catch (e: WhereAbouts.FlightModeException) {
                e.printStackTrace()
            } catch (e: WhereAbouts.PrecisionException) {
                e.printStackTrace()
            }
        }
    }
}