package android.content

/** Stand-in for Android's Context: the packer only needs a files directory. */
open class Context(val filesDir: java.io.File)
