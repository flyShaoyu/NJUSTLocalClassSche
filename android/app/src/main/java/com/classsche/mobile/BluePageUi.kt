package com.classsche.mobile

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal object BluePageUi {
  private fun paper(context:Context)=android.graphics.drawable.GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=16*context.resources.displayMetrics.density}
  fun selector(context:Context,value:String,onClick:()->Unit):View {
    fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
    return LinearLayout(context).apply {
      orientation=LinearLayout.VERTICAL;isClickable=true;isFocusable=true
      val row=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(48);setPadding(dp(12),0,dp(12),0)}
      row.addView(TextView(context).apply{text=value;textSize=14f;setTextColor(Color.rgb(82,107,132));maxLines=2},LinearLayout.LayoutParams(0,-2,1f))
      row.addView(ImageView(context).apply{setImageResource(R.drawable.ic_dropdown);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(20),dp(20)))
      addView(row,LinearLayout.LayoutParams(-1,-2));addView(View(context).apply{setBackgroundColor(Color.rgb(220,229,239))},LinearLayout.LayoutParams(-1,dp(1)))
      contentDescription=value+"，点击选择";setOnClickListener{onClick()}
    }
  }
  fun choices(context:Context,title:String,labels:List<String>,selected:Int,choose:(Int)->Unit){
    MaterialAlertDialogBuilder(context).setBackground(paper(context)).setTitle(title).setSingleChoiceItems(labels.toTypedArray(),selected){dialog,index->dialog.dismiss();choose(index)}.setNegativeButton("取消",null).show()
  }
  fun refreshResult(context:Context,title:String,message:String){
    fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
    val success=title=="更新完成"
    val content=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(20),dp(24),dp(4))}
    val heading=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
    heading.addView(TextView(context).apply{text=if(success)"✓" else "!";textSize=24f;gravity=Gravity.CENTER;setTextColor(Color.parseColor(if(success)"#468E79" else "#B27B36"));background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(Color.parseColor(if(success)"#E8F5EF" else "#FFF4DF"))}},LinearLayout.LayoutParams(dp(44),dp(44)))
    heading.addView(TextView(context).apply{text=title;textSize=18f;setTextColor(Color.rgb(82,107,132));setTypeface(typeface,android.graphics.Typeface.BOLD)},LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(14)})
    content.addView(heading)
    content.addView(TextView(context).apply{text=message;textSize=13f;setTextColor(Color.rgb(110,129,151));setLineSpacing(dp(4).toFloat(),1f);setPadding(0,dp(16),0,dp(8));setTextIsSelectable(true)})
    MaterialAlertDialogBuilder(context).setBackground(paper(context)).setView(content).setPositiveButton("知道了",null).show()
  }
}
