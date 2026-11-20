package com.naminfo.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.PorterDuff
import android.graphics.drawable.AnimatedVectorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.UiThread
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.databinding.BindingAdapter
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.emoji2.emojipicker.EmojiViewItem
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import coil3.dispose
import coil3.load
import coil3.request.placeholder
import coil3.request.transformations
import coil3.size.Dimension
import coil3.transform.CircleCropTransformation
import coil3.transform.RoundedCornersTransformation
import coil3.video.videoFrameMillis
import com.google.android.flexbox.FlexboxLayout
import com.naminfo.BR
import com.naminfo.DiyaOneApplication.Companion.coreContext
import com.naminfo.R
import com.naminfo.contacts.AbstractAvatarModel
import org.linphone.core.ConsolidatedPresence
import org.linphone.core.tools.Log
import com.naminfo.ui.NotoSansFont
import com.naminfo.ui.call.conference.model.ConferenceParticipantDeviceModel
import com.naminfo.ui.call.view.VuMeterView
import com.naminfo.ui.call.view.RoundCornersTextureView
import java.io.File

/**
 * This file contains all the data binding necessary for the app
 */

private const val TAG = "[Data Binding Utils]"

private fun findLifecycleOwner(view: View): LifecycleOwner? {
    return view.findViewTreeLifecycleOwner() ?: run {
        var context = view.context
        while (context is ContextWrapper) {
            if (context is LifecycleOwner) return context
            context = context.baseContext
        }
        null
    }
}

@BindingAdapter("inflatedLifecycleOwner")
fun setInflatedViewStubLifecycleOwner(view: View, enable: Boolean) {
    val binding = DataBindingUtil.bind<ViewDataBinding>(view)
    binding?.lifecycleOwner = findLifecycleOwner(view)
}

@UiThread
@BindingAdapter("entries", "layout")
fun <T> setEntries(
    viewGroup: ViewGroup,
    entries: List<T>?,
    layoutId: Int
) {
    setEntries(viewGroup, entries, layoutId, null, null)
}

@UiThread
@BindingAdapter("entries", "layout", "emptyPlaceholder")
fun <T> setEntries(
    viewGroup: ViewGroup,
    entries: List<T>?,
    layoutId: Int,
    emptyPlaceholderLayoutId: Int
) {
    setEntries(viewGroup, entries, layoutId, emptyPlaceholderLayoutId, null)
}

@UiThread
@BindingAdapter("entries", "layout", "onLongClick")
fun <T> setEntries(
    viewGroup: ViewGroup,
    entries: List<T>?,
    layoutId: Int,
    onLongClick: View.OnLongClickListener?
) {
    setEntries(viewGroup, entries, layoutId, null, onLongClick)
}

@UiThread
@BindingAdapter("entries", "layout", "emptyPlaceholder", "onLongClick")
fun <T> setEntries(
    viewGroup: ViewGroup,
    entries: List<T>?,
    layoutId: Int,
    emptyPlaceholderLayoutId: Int?,
    onLongClick: View.OnLongClickListener?
) {
    viewGroup.removeAllViews()

    if (!entries.isNullOrEmpty()) {
        val inflater = viewGroup.context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        for (entry in entries) {
            val binding = DataBindingUtil.inflate<ViewDataBinding>(
                inflater,
                layoutId,
                viewGroup,
                false
            )

            binding.setVariable(BR.model, entry)
            if (onLongClick != null) {
                binding.setVariable(BR.onLongClickListener, onLongClick)
            }

            val owner = findLifecycleOwner(viewGroup)
            if (owner != null) {
                binding.lifecycleOwner = owner
            } else {
                Log.e(
                    "$TAG Failed to cast viewGroup's context as an Activity, lifecycle owner hasn't be set!"
                )
            }

            viewGroup.addView(binding.root)
        }
    } else if (emptyPlaceholderLayoutId != null) {
        val placeholder = LayoutInflater.from(viewGroup.context).inflate(
            emptyPlaceholderLayoutId,
            viewGroup,
            false
        )
        viewGroup.addView(placeholder)
    }
}

@UiThread
fun AppCompatEditText.removeCharacterAtPosition() {
    val start = selectionStart
    val end = selectionEnd
    if (start > 0) {
        text =
            text?.delete(
                start - 1,
                end
            )
        setSelection(start - 1)
    }
}

@UiThread
fun AppCompatEditText.addCharacterAtPosition(character: String) {
    val start = selectionStart
    text?.insert(start, character)
    setSelection(start + character.length)
}

@UiThread
fun View.setKeyboardInsetListener(lambda: (visible: Boolean) -> Unit) {
    doOnLayout {
        var isKeyboardVisible = ViewCompat.getRootWindowInsets(this)?.isVisible(
            WindowInsetsCompat.Type.ime()
        ) == true

        try {
            lambda(isKeyboardVisible)
        } catch (ise: IllegalStateException) {
            Log.e(
                "$TAG Failed to called lambda after keyboard visibility changed: $ise"
            )
        }

        // See https://issuetracker.google.com/issues/281942480
        ViewCompat.setOnApplyWindowInsetsListener(
            rootView
        ) { view, insets ->
            val keyboardVisibilityChanged = ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (keyboardVisibilityChanged != isKeyboardVisible) {
                isKeyboardVisible = keyboardVisibilityChanged

                try {
                    lambda(isKeyboardVisible)
                } catch (ise: IllegalStateException) {
                    Log.e(
                        "$TAG Failed to called lambda after keyboard visibility changed: $ise"
                    )
                }
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }
    }
}

@UiThread
@BindingAdapter("android:onContextClick")
fun View.setContextListener(longClickListener: View.OnLongClickListener?) {
    longClickListener ?: return

    this.setOnContextClickListener {
        longClickListener.onLongClick(this)
    }
}

@UiThread
@BindingAdapter("android:onContextClick")
fun View.setContextListener(lambda: () -> Unit) {
    this.setOnContextClickListener {
        lambda.invoke()
        true
    }
}

@UiThread
@BindingAdapter("android:src")
fun ImageView.setSourceImageResource(resource: Int) {
    this.setImageResource(resource)
}

@UiThread
@BindingAdapter("android:drawableTint")
fun AppCompatTextView.setDrawableTint(@ColorInt color: Int) {
    coreContext.postOnMainThread {
        for (drawable in compoundDrawablesRelative) {
            drawable?.setTint(color)
        }
    }
}

@UiThread
@BindingAdapter("textFont")
fun AppCompatTextView.font(type: NotoSansFont) {
    try {
        typeface = ResourcesCompat.getFont(context, type.fontRes)
    } catch (e: Exception) {
        Log.e("$TAG Can't load font: $e")
    }
}

@UiThread
@BindingAdapter("presenceIcon")
fun ImageView.setPresenceIcon(presence: ConsolidatedPresence?) {
    setPresenceIcon(presence, false)
}

@UiThread
@BindingAdapter("presenceIcon", "hidePresence")
fun ImageView.setPresenceIcon(presence: ConsolidatedPresence?, hidePresence: Boolean) {
    visibility = if (hidePresence || presence == null || presence == ConsolidatedPresence.Offline) {
        View.GONE
    } else {
        View.VISIBLE
    }

    val icon = when (presence) {
        ConsolidatedPresence.Online -> R.drawable.led_online
        ConsolidatedPresence.DoNotDisturb -> R.drawable.led_do_not_disturb
        ConsolidatedPresence.Busy -> R.drawable.led_away
        else -> R.drawable.led_not_registered
    }
    setImageResource(icon)
}

@BindingAdapter("tint", "disableTint")
fun ImageView.setTintColor(@ColorRes color: Int, disable: Boolean) {
    if (!disable) {
        setColorFilter(context.getColor(color), PorterDuff.Mode.SRC_IN)
    }
}

@BindingAdapter("textColor")
fun AppCompatTextView.setColor(@ColorRes color: Int) {
    setTextColor(context.getColor(color))
}

@UiThread
@BindingAdapter("coilUrl")
fun ImageView.loadUrlImage(url: String?) {
    if (!url.isNullOrEmpty()) {
        load(url) {
            listener(
                onError = { _, result ->
                    Log.e(
                        "$TAG Error getting shortcut icon from URL [$url]: ${result.throwable}"
                    )
                }
            )
        }
    }
}

@UiThread
@BindingAdapter("coilFile")
fun ImageView.loadFileImage(file: String?) {
    if (!file.isNullOrEmpty()) {
        load(file)
    }
}

/** Loads a small reply quote thumbnail without forcing an attachment download. */
@UiThread
@BindingAdapter("coilReplyPreview")
fun ImageView.loadReplyPreview(file: String?) {
    val size = resources.getDimension(R.dimen.chat_bubble_reply_media_preview_size).toInt()
    if (file.isNullOrBlank()) {
        setImageResource(R.drawable.image)
        return
    }
    val source: Any = if (file.startsWith("http", ignoreCase = true) || file.startsWith("content://")) {
        file
    } else {
        val localFile = File(file)
        if (!localFile.isFile || localFile.length() <= 0L) {
            setImageResource(R.drawable.image)
            return
        }
        localFile
    }
    load(source) {
        placeholder(R.drawable.image)
        transformations(
            RoundedCornersTransformation(
                resources.getDimension(R.dimen.chat_bubble_images_rounded_corner_radius)
            )
        )
        size(Dimension(size), Dimension(size))
        listener(onError = { _, _ -> setImageResource(R.drawable.image) })
    }
}

@UiThread
@BindingAdapter("coilBubble", "coilBubbleFallback")
fun ImageView.loadImageForChatBubbleSingle(file: String?, fallback: View?) {
    loadImageForChatBubble(this, file, false, fallback)
}

@UiThread
@BindingAdapter("coilBubbleGrid", "coilBubbleFallback")
fun ImageView.loadImageForChatBubbleGrid(file: String?, fallback: View?) {
    loadImageForChatBubble(this, file, true, fallback)
}

private fun loadImageForChatBubble(
    imageView: ImageView,
    file: String?,
    grid: Boolean,
    fallback: View?
) {
    if (file.isNullOrEmpty()) {
        // Clear any bitmap retained from a recycled ViewHolder so undownloaded items reveal
        // the placeholder icon instead of painting a stale previous photo over it.
        imageView.dispose()
        imageView.setImageDrawable(null)
        imageView.visibility = View.GONE
        return
    }

    val isImage = FileUtils.isExtensionImage((file))
    val isVideo = FileUtils.isExtensionVideo(file)
    if (isImage || isVideo) {
        imageView.visibility = View.VISIBLE
    } else {
        imageView.dispose()
        imageView.setImageDrawable(null)
        imageView.visibility = View.GONE
    }
    if (isImage || isVideo) {
        val dimen = if (grid) {
            imageView.resources.getDimension(R.dimen.chat_bubble_grid_file_size).toInt()
        } else {
            imageView.resources.getDimension(R.dimen.chat_bubble_compact_media_size).toInt()
        }
        // A single-media bubble used an undefined width. Coil then decoded the full source
        // image (often 3–4 MB) before it could draw a small chat thumbnail, which stalled
        // opening a media-heavy conversation for seconds. Both dimensions are an upper bound;
        // the ImageView's fitCenter scale type still preserves the original aspect ratio.
        val width = Dimension(dimen)
        val height = Dimension(dimen)
        val radius = imageView.resources.getDimension(
            R.dimen.chat_bubble_images_rounded_corner_radius
        )

        imageView.visibility = View.VISIBLE
        fallback?.visibility = View.GONE
        if (isVideo) {
            imageView.load(file) {
                placeholder(R.drawable.file_video)
                videoFrameMillis(0)
                transformations(RoundedCornersTransformation(radius))
                size(width, height)
                listener(
                    onError = { _, result ->
                        Log.e(
                            "$TAG Error getting preview picture from video (?) [$file]: ${result.throwable}"
                        )
                        fallback?.visibility = View.VISIBLE
                        imageView.visibility = View.GONE
                    }
                )
            }
        } else {
            imageView.load(file) {
                placeholder(R.drawable.file_image)
                // Can't have a transformation for gif file, breaks animation
                if (FileUtils.getExtensionFromFileName(file) != "gif") {
                    transformations(RoundedCornersTransformation(radius))
                }
                size(width, height)
                listener(
                    onError = { _, result ->
                        Log.e(
                            "$TAG Error getting picture from file [$file]: ${result.throwable}"
                        )
                        fallback?.visibility = View.VISIBLE
                        imageView.visibility = View.GONE
                    }
                )
            }
        }
    }
}

@UiThread
@BindingAdapter("animatedDrawable")
fun ImageView.startAnimatedDrawable(start: Boolean = true) {
    drawable.apply {
        when (this) {
            is AnimatedVectorDrawableCompat -> start()
            is AnimatedVectorDrawable -> start()
            else -> { /* not an animated icon */ }
        }
    }
}

@UiThread
@BindingAdapter("coil")
fun ImageView.loadCircleFileWithCoil(file: String?) {
    if (file != null) {
        load(file) {
            transformations(CircleCropTransformation())
        }
    }
}

@UiThread
@BindingAdapter(
    value = ["coilAvatar", "coilAvatarPicture", "coilAvatarInitials"],
    requireAll = false
)
fun ImageView.loadAvatarWithCoil(
    model: AbstractAvatarModel?,
    picturePath: String?,
    initials: String?
) {
    loadContactPictureWithCoil(this, model, picturePathOverride = picturePath)
}

@UiThread
@BindingAdapter(
    value = ["coilBubbleAvatar", "coilAvatarPicture", "coilAvatarInitials"],
    requireAll = false
)
fun ImageView.loadBubbleAvatarWithCoil(
    model: AbstractAvatarModel?,
    picturePath: String?,
    initials: String?
) {
    val size = R.dimen.avatar_bubble_size
    val initialsSize = R.dimen.avatar_initials_bubble_text_size
    loadContactPictureWithCoil(
        this,
        model,
        size = size,
        textSize = initialsSize,
        picturePathOverride = picturePath
    )
}

@UiThread
@BindingAdapter(
    value = ["coilBigAvatar", "coilAvatarPicture", "coilAvatarInitials"],
    requireAll = false
)
fun ImageView.loadBigAvatarWithCoil(
    model: AbstractAvatarModel?,
    picturePath: String?,
    initials: String?
) {
    val size = R.dimen.avatar_big_size
    val initialsSize = R.dimen.avatar_initials_big_text_size
    loadContactPictureWithCoil(
        this,
        model,
        size = size,
        textSize = initialsSize,
        picturePathOverride = picturePath
    )
}

@UiThread
@BindingAdapter(
    value = ["coilHugeAvatar", "coilAvatarPicture", "coilAvatarInitials"],
    requireAll = false
)
fun ImageView.loadCallAvatarWithCoil(
    model: AbstractAvatarModel?,
    picturePath: String?,
    initials: String?
) {
    val size = R.dimen.avatar_in_call_size
    val initialsSize = R.dimen.avatar_initials_call_text_size
    loadContactPictureWithCoil(
        this,
        model,
        size = size,
        textSize = initialsSize,
        picturePathOverride = picturePath
    )
}

@SuppressLint("ResourceType")
private fun loadContactPictureWithCoil(
    imageView: ImageView,
    model: AbstractAvatarModel?,
    @DimenRes size: Int = 0,
    @DimenRes textSize: Int = 0,
    picturePathOverride: String? = null
) {
    imageView.dispose()

    val context = imageView.context
    if (model != null) {
        if (model.forceConferenceIcon.value == true) {
            imageView.load(R.drawable.inset_video_conference)
            return
        } else if (model.forceConversationIcon.value == true) {
            imageView.load(R.drawable.inset_users_three)
            return
        }

        val image = (picturePathOverride ?: model.picturePath.value).orEmpty()
            .takeIf { it != "null" }
            .orEmpty()
        if (image.isNotEmpty()) {
            // Keep a stable initials avatar in recycled list cells until the
            // remote profile image is ready, and expose the image fetch with a
            // small ring around that avatar.
            imageView.load(getErrorImageLoader(context, model, size, textSize))
            imageView.setAvatarLoading(true)
            imageView.load(image) {
                if (image.startsWith("http://", ignoreCase = true) ||
                    image.startsWith("https://", ignoreCase = true)
                ) {
                    val epoch = com.naminfo.contacts.MobionContactsService.currentAvatarCacheEpoch()
                    memoryCacheKey("$image#v=$epoch")
                    diskCacheKey("$image#v=$epoch")
                }
                transformations(CircleCropTransformation())
                listener(
                    onSuccess = { _, _ ->
                        imageView.setAvatarLoading(false)
                    },
                    onError = { _, _ ->
                        imageView.setAvatarLoading(false)
                        imageView.load(getErrorImageLoader(context, model, size, textSize))
                    }
                )
            }
        } else {
            imageView.setAvatarLoading(false)
            imageView.load(getErrorImageLoader(context, model, size, textSize))
        }
    }
}

@UiThread
private fun ImageView.setAvatarLoading(isLoading: Boolean) {
    (parent as? View)?.findViewById<View>(R.id.avatar_loading)?.visibility =
        if (isLoading) View.VISIBLE else View.GONE
}

private fun getErrorImageLoader(
    context: Context,
    model: AbstractAvatarModel,
    size: Int,
    textSize: Int
): Any {
    val initials = model.initials.value.orEmpty()
    return if (initials.isEmpty() || initials == "+" || model.skipInitials.value == true) {
        if (model.defaultToConferenceIcon.value == true) {
            R.drawable.inset_video_conference
        } else if (model.defaultToConversationIcon.value == true) {
            R.drawable.inset_users_three
        } else {
            R.drawable.inset_user_circle
        }
    } else {
        ImageUtils.generatedAvatarIfNeededAndReturnPath(context, initials)
    }
}

@UiThread
@BindingAdapter("participantTextureView")
fun setParticipantTextureView(
    textureView: TextureView,
    model: ConferenceParticipantDeviceModel
) {
    model.setTextureView(textureView)
}

@UiThread
@BindingAdapter("vuMeterPercentage")
fun setVuMeterPercentage(view: VuMeterView, percentage: Float) {
    view.setVuMeterPercentage(percentage)
}

@UiThread
@BindingAdapter("onValueChanged")
fun AppCompatEditText.editTextSetting(lambda: () -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            lambda()
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })
}

@BindingAdapter("android:layout_marginBottom")
fun setConstraintLayoutBottomMargin(view: View, margins: Float) {
    val params = view.layoutParams as ViewGroup.MarginLayoutParams
    val m = margins.toInt()
    params.setMargins(params.leftMargin, params.topMargin, params.rightMargin, m)
    view.layoutParams = params
}

@BindingAdapter("android:layout_marginTop")
fun setConstraintLayoutTopMargin(view: View, margins: Float) {
    val params = view.layoutParams as ViewGroup.MarginLayoutParams
    val m = margins.toInt()
    params.setMargins(params.leftMargin, m, params.rightMargin, params.bottomMargin)
    view.layoutParams = params
}

@BindingAdapter("android:layout_marginStart")
fun setConstraintLayoutStartMargin(view: View, margins: Float) {
    val params = view.layoutParams as ViewGroup.MarginLayoutParams
    val m = margins.toInt()
    params.marginStart = m
    view.layoutParams = params
}

@BindingAdapter("layout_constraintHorizontal_bias")
fun setConstraintLayoutChildHorizontalBias(view: View, horizontalBias: Float) {
    val params = view.layoutParams as ConstraintLayout.LayoutParams
    params.horizontalBias = horizontalBias
    view.layoutParams = params
}

@BindingAdapter("layout_constraintHeight_max")
fun setConstraintLayoutHeightMax(view: View, dp: Float) {
    val layout = view.parent as ConstraintLayout
    val cs = ConstraintSet()
    cs.clone(layout)
    cs.constrainMaxHeight(view.id, dp.toInt())
    cs.applyTo(layout)
}

@BindingAdapter("layout_constraintWidth_max")
fun setConstraintLayoutWidthMax(view: View, dp: Float) {
    val layout = view.parent as ConstraintLayout
    val cs = ConstraintSet()
    cs.clone(layout)
    cs.constrainMaxWidth(view.id, dp.toInt())
    cs.applyTo(layout)
}

@BindingAdapter("roundCornersRadius")
fun RoundCornersTextureView.setRoundCornersRadius(radius: Float) {
    setRadius(radius)
}

@BindingAdapter("passwordInputType")
fun setInputTypeAndFont(editText: EditText, type: Int) {
    editText.inputType = type
    // Typeface must be set again...
    editText.typeface = ResourcesCompat.getFont(editText.context, R.font.noto_sans)
}

@BindingAdapter("focusNextOnInput")
fun focusNextOnInput(editText: EditText, enabled: Boolean) {
    if (!enabled) return

    editText.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            if (!s.isNullOrEmpty()) {
                editText.onEditorAction(EditorInfo.IME_ACTION_NEXT)
            }
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })
}

@BindingAdapter("validateOnInput")
fun validateOnInput(editText: EditText, onValidate: () -> (Unit)) {
    editText.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            if (!s.isNullOrEmpty()) {
                editText.onEditorAction(EditorInfo.IME_ACTION_DONE)
                onValidate.invoke()
            }
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })
}

@BindingAdapter("layout_wrapBefore")
fun setFlexboxLayoutWrapBefore(view: View, wrap: Boolean = false) {
    val params = view.layoutParams as FlexboxLayout.LayoutParams
    params.isWrapBefore = wrap
    view.layoutParams = params
}

@BindingAdapter("seekBarListener")
fun setSeekBarListener(seekBar: SeekBar, listener: SeekBar.OnSeekBarChangeListener) {
    seekBar.setOnSeekBarChangeListener(listener)
}

@BindingAdapter("emojiPickedListener")
fun EmojiPickerView.setEmojiPickedListener(listener: EmojiPickedListener) {
    setOnEmojiPickedListener { emoji ->
        listener.onEmojiPicked(emoji)
    }
}

interface EmojiPickedListener {
    fun onEmojiPicked(item: EmojiViewItem)
}

@SuppressLint("ClickableViewAccessibility")
@BindingAdapter("onTouchListener")
fun View.setTouchListener(listener: TouchListener) {
    setOnTouchListener { view, event ->
        return@setOnTouchListener when (event.action) {
            MotionEvent.ACTION_DOWN -> listener.onPressed(view)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> listener.onReleased(view)
            else -> false
        }
    }
}

interface TouchListener {
    fun onPressed(view: View): Boolean

    fun onReleased(view: View): Boolean
}
