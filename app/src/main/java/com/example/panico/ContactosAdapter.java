package com.example.panico;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ContactosAdapter extends RecyclerView.Adapter<ContactosAdapter.VH> {

    interface Listener {
        void onMakePrimary(int position);
        void onDelete(int position);
    }

    private final List<EmergencyContact> data = new ArrayList<>();
    private final Listener listener;

    public ContactosAdapter(Listener l) { this.listener = l; }

    public void submit(List<EmergencyContact> list) {
        data.clear(); data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contacto, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        EmergencyContact c = data.get(pos);
        h.tvName.setText(c.name);
        h.tvPhone.setText(c.phoneFormatted);
        h.tvPrimary.setVisibility(c.isPrimary ? View.VISIBLE : View.GONE);
        h.btnPrimary.setOnClickListener(v -> listener.onMakePrimary(h.getBindingAdapterPosition()));
        h.btnDelete.setOnClickListener(v -> listener.onDelete(h.getBindingAdapterPosition()));

        // Avatar con inicial
        String initial = c.name != null && !c.name.isEmpty() ? c.name.substring(0,1).toUpperCase() : "C";
        h.imgAvatar.setImageDrawable(new BitmapDrawable(h.itemView.getResources(),
                drawCircleInitial(h.itemView.getWidth() > 0 ? h.itemView.getWidth() : 44, initial,
                        ContextCompat.getColor(h.itemView.getContext(), R.color.green_primary))));
    }

    @Override public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvPhone, tvPrimary;
        ImageButton btnPrimary, btnDelete;
        ImageView imgAvatar;
        VH(@NonNull View v) {
            super(v);
            tvName = v.findViewById(R.id.tvName);
            tvPhone = v.findViewById(R.id.tvPhone);
            tvPrimary = v.findViewById(R.id.tvPrimaryBadge);
            btnPrimary = v.findViewById(R.id.btnMakePrimary);
            btnDelete = v.findViewById(R.id.btnDelete);
            imgAvatar = v.findViewById(R.id.imgAvatar);
        }
    }

    private static Bitmap drawCircleInitial(int sizeDp, String initial, int color) {
        int size = Math.max(88, 88); // px aproximados
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawCircle(size/2f, size/2f, size/2f, p);

        p.setColor(0xFFFFFFFF);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(size * 0.45f);
        Paint.FontMetrics fm = p.getFontMetrics();
        float y = size/2f - (fm.ascent + fm.descent)/2f;
        c.drawText(initial, size/2f, y, p);
        return bmp;
    }
}
