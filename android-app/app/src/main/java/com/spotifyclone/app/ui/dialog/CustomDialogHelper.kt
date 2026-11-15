package com.spotifyclone.app.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.Window
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.spotifyclone.app.R

object CustomDialogHelper {

    fun showConfirmDialog(
        context: Context,
        title: String,
        message: String,
        confirmText: String = "Confirm",
        isDestructive: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_confirm, null)
        dialog.setContentView(view)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val textTitle = view.findViewById<TextView>(R.id.dialog_title)
        val textMessage = view.findViewById<TextView>(R.id.dialog_message)
        val btnCancel = view.findViewById<TextView>(R.id.btn_dialog_cancel)
        val btnConfirm = view.findViewById<TextView>(R.id.btn_dialog_confirm)

        textTitle.text = title
        textMessage.text = message
        btnConfirm.text = confirmText

        if (isDestructive) {
            btnConfirm.setBackgroundResource(R.drawable.bg_pill_button_danger)
            btnConfirm.setTextColor(ContextCompat.getColor(context, R.color.spotify_white))
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }

        dialog.show()
    }

    fun showInputDialog(
        context: Context,
        title: String,
        message: String,
        hint: String = "Name",
        confirmText: String = "Create",
        onConfirm: (String) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_input, null)
        dialog.setContentView(view)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val textTitle = view.findViewById<TextView>(R.id.input_dialog_title)
        val textMessage = view.findViewById<TextView>(R.id.input_dialog_message)
        val editInput = view.findViewById<EditText>(R.id.edit_dialog_input)
        val btnCancel = view.findViewById<TextView>(R.id.btn_input_cancel)
        val btnConfirm = view.findViewById<TextView>(R.id.btn_input_confirm)

        textTitle.text = title
        textMessage.text = message
        editInput.hint = hint
        btnConfirm.text = confirmText

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val text = editInput.text.toString().trim()
            if (text.isNotEmpty()) {
                dialog.dismiss()
                onConfirm(text)
            }
        }

        dialog.show()
    }

    fun showInfoDialog(
        context: Context,
        title: String,
        body: String
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_info, null)
        dialog.setContentView(view)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val textTitle = view.findViewById<TextView>(R.id.info_dialog_title)
        val textBody = view.findViewById<TextView>(R.id.info_dialog_body)
        val btnClose = view.findViewById<TextView>(R.id.btn_info_close)

        textTitle.text = title
        textBody.text = body

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
