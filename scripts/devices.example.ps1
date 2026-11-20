# Copy to scripts\devices.local.ps1 (untracked) and put the real hardware serials (adb shell getprop ro.serialno).
$TF_DEVICES = @{
    moto  = @('ZY00000000')     # Motorola One 5G Ace, Android 11
    op12r = @('00000000')       # OnePlus 12R, Android 16
}
