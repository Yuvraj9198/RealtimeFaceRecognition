package com.example.imageclassificationlivefeed

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FaceAdapter(
    private val faceList: MutableList<FaceItem>,
    private val onDeleteClick: (Int) -> Unit,
    private val onEditClickListener: (Int, String) -> Unit
) : RecyclerView.Adapter<FaceAdapter.FaceViewHolder>() {

    inner class FaceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameTextView: TextView = itemView.findViewById(R.id.nameTextView)
        val faceImageView: android.widget.ImageView = itemView.findViewById(R.id.dlg_image)
        val deleteButton: Button = itemView.findViewById(R.id.deleteButton)
        val editButton: Button = itemView.findViewById(R.id.editButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FaceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_face, parent, false)
        return FaceViewHolder(view)
    }

    override fun onBindViewHolder(holder: FaceViewHolder, position: Int) {
        val item = faceList[position]
        holder.nameTextView.text = item.name
        // Set the face image if available; otherwise use a placeholder if needed.
        if (item.image != null) {
            holder.faceImageView.setImageBitmap(item.image)
        } else {
            holder.faceImageView.setImageResource(android.R.color.darker_gray)
        }
        holder.editButton.setOnClickListener { onEditClickListener(item.id, item.name) }
        holder.deleteButton.setOnClickListener { onDeleteClick(item.id) }
    }

    override fun getItemCount(): Int = faceList.size

    fun removeItem(position: Int) {
        faceList.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, faceList.size)
    }
}
