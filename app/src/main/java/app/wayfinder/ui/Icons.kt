package app.wayfinder.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.MagnifyingGlass as RMagnifyingGlass
import com.adamglin.phosphoricons.regular.ArrowLeft as RArrowLeft
import com.adamglin.phosphoricons.regular.X as RX
import com.adamglin.phosphoricons.regular.DownloadSimple as RDownloadSimple
import com.adamglin.phosphoricons.regular.GearSix as RGearSix
import com.adamglin.phosphoricons.regular.Trash as RTrash
import com.adamglin.phosphoricons.regular.ArrowsDownUp as RArrowsDownUp
import com.adamglin.phosphoricons.regular.Crosshair as RCrosshair
import com.adamglin.phosphoricons.regular.MapPin as RMapPin
import com.adamglin.phosphoricons.regular.Clock as RClock
import com.adamglin.phosphoricons.regular.CaretRight as RCaretRight
import com.adamglin.phosphoricons.regular.CaretDown as RCaretDown
import com.adamglin.phosphoricons.regular.Train as RTrain
import com.adamglin.phosphoricons.regular.TrainSimple as RTrainSimple
import com.adamglin.phosphoricons.regular.Bus as RBus
import com.adamglin.phosphoricons.regular.SlidersHorizontal as RSlidersHorizontal
import com.adamglin.phosphoricons.regular.Mountains as RMountains
import com.adamglin.phosphoricons.regular.MapTrifold as RMapTrifold
import com.adamglin.phosphoricons.regular.ArrowBendUpLeft as RArrowBendUpLeft
import com.adamglin.phosphoricons.regular.ArrowBendUpRight as RArrowBendUpRight
import com.adamglin.phosphoricons.regular.ArrowUp as RArrowUp
import com.adamglin.phosphoricons.regular.ArrowUUpLeft as RArrowUUpLeft
import com.adamglin.phosphoricons.regular.ArrowsClockwise as RArrowsClockwise
import com.adamglin.phosphoricons.regular.Bicycle as RBicycle
import com.adamglin.phosphoricons.regular.Tram as RTram
import com.adamglin.phosphoricons.regular.Subway as RSubway
import com.adamglin.phosphoricons.regular.Boat as RBoat
import com.adamglin.phosphoricons.regular.PersonSimpleWalk as RPersonSimpleWalk
import com.adamglin.phosphoricons.regular.PersonSimpleBike as RPersonSimpleBike
import com.adamglin.phosphoricons.regular.Airplane as RAirplane
import com.adamglin.phosphoricons.regular.WarningCircle as RWarningCircle
import com.adamglin.phosphoricons.regular.FlagCheckered as RFlagCheckered
import com.adamglin.phosphoricons.regular.ArrowRight as RArrowRight
import com.adamglin.phosphoricons.regular.Path as RPath
import com.adamglin.phosphoricons.regular.Stack as RStack
import com.adamglin.phosphoricons.regular.Circle as RCircle
import com.adamglin.phosphoricons.regular.Signpost as RSignpost
import com.adamglin.phosphoricons.regular.ArrowClockwise as RArrowClockwise
import com.adamglin.phosphoricons.fill.NavigationArrow as FNavigationArrow
import com.adamglin.phosphoricons.fill.MapPin as FMapPin
import com.adamglin.phosphoricons.fill.Train as FTrain
import com.adamglin.phosphoricons.fill.TrainSimple as FTrainSimple
import com.adamglin.phosphoricons.fill.Bus as FBus
import com.adamglin.phosphoricons.fill.MapTrifold as FMapTrifold
import com.adamglin.phosphoricons.fill.Bicycle as FBicycle
import com.adamglin.phosphoricons.fill.Tram as FTram
import com.adamglin.phosphoricons.fill.Subway as FSubway
import com.adamglin.phosphoricons.fill.Boat as FBoat
import com.adamglin.phosphoricons.fill.PersonSimpleWalk as FPersonSimpleWalk
import com.adamglin.phosphoricons.fill.PersonSimpleBike as FPersonSimpleBike
import com.adamglin.phosphoricons.fill.Airplane as FAirplane
import com.adamglin.phosphoricons.fill.Stack as FStack
import com.adamglin.phosphoricons.fill.Circle as FCircle
import com.adamglin.phosphoricons.fill.ForkKnife as FForkKnife
import com.adamglin.phosphoricons.fill.ShoppingBag as FShoppingBag
import com.adamglin.phosphoricons.fill.Tree as FTree
import com.adamglin.phosphoricons.fill.FirstAid as FFirstAid
import com.adamglin.phosphoricons.fill.MaskHappy as FMaskHappy
import com.adamglin.phosphoricons.fill.Church as FChurch
import com.adamglin.phosphoricons.fill.GraduationCap as FGraduationCap
import com.adamglin.phosphoricons.fill.Bed as FBed
import com.adamglin.phosphoricons.fill.Bank as FBank
import com.adamglin.phosphoricons.fill.GasPump as FGasPump
import com.adamglin.phosphoricons.fill.Star as FStar
import com.adamglin.phosphoricons.fill.Path as FPath
import com.adamglin.phosphoricons.fill.GpsFix as FGpsFix
import com.adamglin.phosphoricons.fill.FlagCheckered as FFlagCheckered
import com.adamglin.phosphoricons.bold.X as BX
import com.adamglin.phosphoricons.bold.Plus as BPlus
import com.adamglin.phosphoricons.bold.Check as BCheck
import com.adamglin.phosphoricons.bold.ArrowLeft as BArrowLeft

/** Phosphor icons used across the app (https://phosphoricons.com). */
object Ph {
    val MagnifyingGlass: ImageVector get() = PhosphorIcons.Regular.RMagnifyingGlass
    val ArrowLeft: ImageVector get() = PhosphorIcons.Regular.RArrowLeft
    val X: ImageVector get() = PhosphorIcons.Regular.RX
    val ArrowsDownUp: ImageVector get() = PhosphorIcons.Regular.RArrowsDownUp
    val Crosshair: ImageVector get() = PhosphorIcons.Regular.RCrosshair
    val MapPin: ImageVector get() = PhosphorIcons.Regular.RMapPin
    val Clock: ImageVector get() = PhosphorIcons.Regular.RClock
    val CaretRight: ImageVector get() = PhosphorIcons.Regular.RCaretRight
    val CaretDown: ImageVector get() = PhosphorIcons.Regular.RCaretDown
    val Train: ImageVector get() = PhosphorIcons.Regular.RTrain
    val TrainSimple: ImageVector get() = PhosphorIcons.Regular.RTrainSimple
    val Bus: ImageVector get() = PhosphorIcons.Regular.RBus
    val SlidersHorizontal: ImageVector get() = PhosphorIcons.Regular.RSlidersHorizontal
    val Mountains: ImageVector get() = PhosphorIcons.Regular.RMountains
    val MapTrifold: ImageVector get() = PhosphorIcons.Regular.RMapTrifold
    val ArrowBendUpLeft: ImageVector get() = PhosphorIcons.Regular.RArrowBendUpLeft
    val ArrowBendUpRight: ImageVector get() = PhosphorIcons.Regular.RArrowBendUpRight
    val ArrowUp: ImageVector get() = PhosphorIcons.Regular.RArrowUp
    val ArrowUUpLeft: ImageVector get() = PhosphorIcons.Regular.RArrowUUpLeft
    val ArrowsClockwise: ImageVector get() = PhosphorIcons.Regular.RArrowsClockwise
    val Bicycle: ImageVector get() = PhosphorIcons.Regular.RBicycle
    val Tram: ImageVector get() = PhosphorIcons.Regular.RTram
    val Subway: ImageVector get() = PhosphorIcons.Regular.RSubway
    val Boat: ImageVector get() = PhosphorIcons.Regular.RBoat
    val PersonSimpleWalk: ImageVector get() = PhosphorIcons.Regular.RPersonSimpleWalk
    val PersonSimpleBike: ImageVector get() = PhosphorIcons.Regular.RPersonSimpleBike
    val Airplane: ImageVector get() = PhosphorIcons.Regular.RAirplane
    val WarningCircle: ImageVector get() = PhosphorIcons.Regular.RWarningCircle
    val FlagCheckered: ImageVector get() = PhosphorIcons.Regular.RFlagCheckered
    val ArrowRight: ImageVector get() = PhosphorIcons.Regular.RArrowRight
    val Path: ImageVector get() = PhosphorIcons.Regular.RPath
    val Stack: ImageVector get() = PhosphorIcons.Regular.RStack
    val Circle: ImageVector get() = PhosphorIcons.Regular.RCircle
    val Signpost: ImageVector get() = PhosphorIcons.Regular.RSignpost
    val ArrowClockwise: ImageVector get() = PhosphorIcons.Regular.RArrowClockwise
    val DownloadSimple: ImageVector get() = PhosphorIcons.Regular.RDownloadSimple
    val GearSix: ImageVector get() = PhosphorIcons.Regular.RGearSix
    val Trash: ImageVector get() = PhosphorIcons.Regular.RTrash

    object Filled {
        val NavigationArrow: ImageVector get() = PhosphorIcons.Fill.FNavigationArrow
        val MapPin: ImageVector get() = PhosphorIcons.Fill.FMapPin
        val Train: ImageVector get() = PhosphorIcons.Fill.FTrain
        val TrainSimple: ImageVector get() = PhosphorIcons.Fill.FTrainSimple
        val Bus: ImageVector get() = PhosphorIcons.Fill.FBus
        val MapTrifold: ImageVector get() = PhosphorIcons.Fill.FMapTrifold
        val Bicycle: ImageVector get() = PhosphorIcons.Fill.FBicycle
        val Tram: ImageVector get() = PhosphorIcons.Fill.FTram
        val Subway: ImageVector get() = PhosphorIcons.Fill.FSubway
        val Boat: ImageVector get() = PhosphorIcons.Fill.FBoat
        val PersonSimpleWalk: ImageVector get() = PhosphorIcons.Fill.FPersonSimpleWalk
        val PersonSimpleBike: ImageVector get() = PhosphorIcons.Fill.FPersonSimpleBike
        val Airplane: ImageVector get() = PhosphorIcons.Fill.FAirplane
        val Stack: ImageVector get() = PhosphorIcons.Fill.FStack
        val Circle: ImageVector get() = PhosphorIcons.Fill.FCircle
        val ForkKnife: ImageVector get() = PhosphorIcons.Fill.FForkKnife
        val ShoppingBag: ImageVector get() = PhosphorIcons.Fill.FShoppingBag
        val Tree: ImageVector get() = PhosphorIcons.Fill.FTree
        val FirstAid: ImageVector get() = PhosphorIcons.Fill.FFirstAid
        val MaskHappy: ImageVector get() = PhosphorIcons.Fill.FMaskHappy
        val Church: ImageVector get() = PhosphorIcons.Fill.FChurch
        val GraduationCap: ImageVector get() = PhosphorIcons.Fill.FGraduationCap
        val Bed: ImageVector get() = PhosphorIcons.Fill.FBed
        val Bank: ImageVector get() = PhosphorIcons.Fill.FBank
        val GasPump: ImageVector get() = PhosphorIcons.Fill.FGasPump
        val Star: ImageVector get() = PhosphorIcons.Fill.FStar
        val Path: ImageVector get() = PhosphorIcons.Fill.FPath
        val GpsFix: ImageVector get() = PhosphorIcons.Fill.FGpsFix
        val FlagCheckered: ImageVector get() = PhosphorIcons.Fill.FFlagCheckered
    }

    object Strong {
        val Plus: ImageVector get() = PhosphorIcons.Bold.BPlus
        val Check: ImageVector get() = PhosphorIcons.Bold.BCheck
        val X: ImageVector get() = PhosphorIcons.Bold.BX
        val ArrowLeft: ImageVector get() = PhosphorIcons.Bold.BArrowLeft
    }
}
