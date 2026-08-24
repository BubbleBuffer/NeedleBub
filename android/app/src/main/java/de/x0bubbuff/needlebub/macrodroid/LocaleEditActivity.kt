package de.x0bubbuff.needlebub.macrodroid

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import de.x0bubbuff.needlebub.NeedleBubApplication

class LocaleEditActivity : Activity() {
    private lateinit var packs: List<de.x0bubbuff.needlebub.packs.InstalledPack>
    private lateinit var spinner: Spinner
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packs = (application as NeedleBubApplication).packStore.list().filter { "external" in it.manifest.surfaces }
        val prior = intent.getBundleExtra(LocaleProtocol.EXTRA_BUNDLE)

        spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@LocaleEditActivity,
                android.R.layout.simple_spinner_dropdown_item,
                packs.map { "${it.manifest.name} · ${it.manifest.version}" },
            )
            val selected = packs.indexOfFirst { it.manifest.id == prior?.getString(LocaleProtocol.KEY_CAPABILITY) }
            if (selected >= 0) setSelection(selected)
        }
        input = EditText(this).apply {
            hint = "Message or MacroDroid Magic Text"
            minLines = 4
            setText(prior?.getString(LocaleProtocol.KEY_INPUT).orEmpty())
        }
        val save = Button(this).apply {
            text = "Use NeedleBub"
            isEnabled = packs.isNotEmpty()
            setOnClickListener { save() }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(this@LocaleEditActivity).apply {
                text = if (packs.isEmpty()) "Install an external capability pack in NeedleBub first." else "Select a local capability pack"
                textSize = 18f
            })
            addView(spinner, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(input, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(save, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        setContentView(layout)
    }

    private fun save() {
        val pack = packs.getOrNull(spinner.selectedItemPosition) ?: return
        val config = Bundle().apply {
            putString(LocaleProtocol.KEY_CAPABILITY, pack.manifest.id)
            putString(LocaleProtocol.KEY_INPUT, input.text.toString())
            putStringArray(LocaleProtocol.EXTRA_VARIABLE_REPLACE_KEYS, arrayOf(LocaleProtocol.KEY_INPUT))
        }
        val variables = buildList {
            add("%nb_matched\nWhether the pack matched")
            add("%nb_tool\nNeedle tool name")
            add("%nb_result_json\nRaw result JSON")
            add("%nb_error_code\nStable NeedleBub error code")
            pack.manifest.outputs.keys.forEach { add("%$it\n${it.removePrefix("nb_")}") }
        }.distinct().toTypedArray()
        setResult(RESULT_OK, android.content.Intent().apply {
            putExtra(LocaleProtocol.EXTRA_BUNDLE, config)
            putExtra(LocaleProtocol.EXTRA_BLURB, "Run ${pack.manifest.name} locally")
            putExtra(LocaleProtocol.EXTRA_VARIABLES, variables)
        })
        finish()
    }
}
