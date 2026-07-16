package android.app

import android.content.Context
import android.content.Intent
import android.os.Bundle

open class Activity : Context() {
    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onStart() {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onStop() {}
    open fun onDestroy() {}

    open fun setContentView(layoutResID: Int) {}
    open fun findViewById(id: Int): android.view.View? = null

    open fun startActivity(intent: Intent) {}
    open fun startActivityForResult(intent: Intent, requestCode: Int) {}
    open fun finish() {}

    open fun getIntent(): Intent? = null

    open fun getPreferences(mode: Int): android.content.SharedPreferences {
        return getSharedPreferences(getPackageName() + "_preferences", mode)
    }
}
