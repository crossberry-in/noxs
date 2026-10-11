/*
 * Noxs — original implementation.
 * About screen: identity, licensing, non-affiliation statement.
 */
package com.crossberry.noxs.ui

import android.os.Bundle
import android.view.View
import android.text.util.Linkify
import android.text.method.LinkMovementMethod
import androidx.appcompat.app.AppCompatActivity
import com.crossberry.noxs.BuildConfig
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityManagerBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.screenTitle.text = getString(R.string.title_about)
        binding.screenHint.visibility = View.VISIBLE
        binding.screenHint.text = getString(R.string.about_body)
        binding.screenHint.autoLinkMask = Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES
        binding.screenHint.movementMethod = LinkMovementMethod.getInstance()
        binding.btnAction.visibility = View.GONE
        binding.inputBar.visibility = View.GONE
        binding.recycler.visibility = View.GONE

        binding.screenEmpty.visibility = View.VISIBLE
        binding.screenEmpty.text = getString(R.string.about_version, BuildConfig.VERSION_NAME) + "\n\n" +
            getString(R.string.about_licenses)
    }
}
