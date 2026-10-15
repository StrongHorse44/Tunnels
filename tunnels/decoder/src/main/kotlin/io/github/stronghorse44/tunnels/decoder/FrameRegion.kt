package io.github.stronghorse44.tunnels.decoder

import android.os.Parcel
import android.os.Parcelable
import android.os.SharedMemory

/**
 * The host-created, fixed-size shared memory region the service writes one frame into. It travels host to service
 * only: the host never unparcels anything from the service except DecodeReply's ints. (A wrapper, so the AIDL needs
 * nothing from the SDK's declaration of SharedMemory.)
 */
class FrameRegion(val memory: SharedMemory) : Parcelable {
    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeTypedObject(memory, flags)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<FrameRegion> = object : Parcelable.Creator<FrameRegion> {
            override fun createFromParcel(source: Parcel): FrameRegion = FrameRegion(source.readTypedObject(SharedMemory.CREATOR)!!)

            override fun newArray(size: Int): Array<FrameRegion?> = arrayOfNulls(size)
        }
    }
}
