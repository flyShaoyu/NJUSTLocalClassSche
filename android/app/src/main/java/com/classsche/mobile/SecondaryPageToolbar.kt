package com.classsche.mobile

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

/** Same compact geometry as the existing login/settings toolbar. */
internal object SecondaryPageToolbar {
  fun applyGeometry(toolbar:MaterialToolbar,statusTop:Int){
    fun dp(n:Int)=(n*toolbar.resources.displayMetrics.density).toInt()
    if(toolbar.minimumHeight!=0)toolbar.minimumHeight=0
    toolbar.setPadding(0,statusTop+dp(4),0,dp(4))
    if(toolbar.layoutParams.height!=statusTop+dp(52))toolbar.layoutParams=toolbar.layoutParams.apply{height=statusTop+dp(52)}
    toolbar.contentInsetEndWithActions=dp(12)
    toolbar.popupTheme=R.style.ThemeOverlay_ClassSche_Popup
    for(i in 0 until toolbar.childCount){val child=toolbar.getChildAt(i);if(child is androidx.appcompat.widget.ActionMenuView){if(child.minimumHeight!=0)child.minimumHeight=0;if(child.layoutParams.height!=dp(44))child.layoutParams=child.layoutParams.apply{height=dp(44)}}}
  }
  fun bind(root: View, toolbar: MaterialToolbar,domainSubtitle:Boolean=false) {
    fun dp(value: Int) = (value * root.resources.displayMetrics.density).toInt()
    toolbar.minimumHeight = 0
    toolbar.isTitleCentered = true
    toolbar.isSubtitleCentered = true
    toolbar.popupTheme=R.style.ThemeOverlay_ClassSche_Popup
    toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
    toolbar.navigationIcon?.setTint(Color.WHITE)
    toolbar.setContentInsetStartWithNavigation(dp(20))
    if(!domainSubtitle)applyGeometry(toolbar,ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.systemBars())?.top?:0)
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      if(domainSubtitle)toolbar.setPadding(0,bars.top+dp(4),0,dp(4))else applyGeometry(toolbar,bars.top)
      root.setPadding(bars.left, 0, bars.right, bars.bottom)
      insets
    }
    toolbar.post {
      for (index in 0 until toolbar.childCount) {
        val child = toolbar.getChildAt(index)
        if (child is ImageButton) {
          child.layoutParams = child.layoutParams.apply {
            width = dp(44); height = dp(44)
            if (this is ViewGroup.MarginLayoutParams) { marginStart = dp(8); marginEnd = dp(8) }
          }
          child.minimumWidth = dp(44); child.minimumHeight = dp(44)
          child.setPadding(0, 0, 0, 0)
          child.scaleType = ImageView.ScaleType.CENTER
          child.imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
      }
    }
    ViewCompat.requestApplyInsets(root)
  }
}
