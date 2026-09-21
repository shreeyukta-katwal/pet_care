package com.petcare.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.petcare.app.databinding.ActivityMainBinding

/**
 * MainActivity – the single Activity for the entire PetCare application.
 *
 * Hosts the [NavHostFragment] that manages all fragment back-stack transitions.
 * The Activity itself has no business logic; everything is delegated to Fragments
 * and their ViewModels following the MVVM + Navigation Component pattern.
 *
 * ## Navigation
 * The nav graph (res/navigation/nav_graph.xml) defines all destinations.
 * Fragments navigate using NavController actions (safe-args where needed).
 *
 * ## ViewBinding
 * Uses [ActivityMainBinding] generated from activity_main.xml.
 * Binding is cleared in [onDestroy] to prevent memory leaks.
 */
class MainActivity : AppCompatActivity() {

    /** ViewBinding instance for the activity layout. Null after onDestroy. */
    private var _binding: ActivityMainBinding? = null

    /**
     * Non-null accessor for [_binding].
     * Only valid between [onCreate] and [onDestroy].
     */
    private val binding get() = _binding!!

    /** NavController obtained from the NavHostFragment. */
    private lateinit var navController: NavController

    /**
     * Initialises the Activity, inflates the layout via ViewBinding, and
     * connects the NavController to the NavHostFragment.
     *
     * @param savedInstanceState Bundle from a previous instance (rotation, etc.).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflate the layout using ViewBinding (replaces setContentView)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Obtain the NavController from the NavHostFragment defined in the layout
        val navHostFragment = supportFragmentManager
            .findFragmentById(binding.navHostFragment.id) as NavHostFragment
        navController = navHostFragment.navController
    }

    /**
     * Delegates the system Back button press to the NavController so that
     * fragment back-stack navigation works correctly.
     *
     * @return True if the NavController handled the event, false otherwise
     *         (in which case the Activity processes it normally).
     */
    override fun onSupportNavigateUp(): Boolean =
        navController.navigateUp() || super.onSupportNavigateUp()

    /**
     * Clears the binding reference to prevent memory leaks after the Activity
     * is destroyed (e.g., on rotation or when the app is closed).
     */
    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }
}
