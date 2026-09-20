import { Host } from "@expo/ui"
import { Slider as ComposeSlider } from "@expo/ui/jetpack-compose"
import {
  fillMaxWidth,
  height,
  testID as composeTestID,
} from "@expo/ui/jetpack-compose/modifiers"
import { Slider as SwiftSlider } from "@expo/ui/swift-ui"
import {
  accessibilityLabel as swiftAccessibilityLabel,
  disabled as swiftDisabled,
  tint,
} from "@expo/ui/swift-ui/modifiers"
import { useRef } from "react"
import { Platform, StyleSheet, View } from "react-native"
import { useCSSVariable } from "uniwind"

import { cn } from "@/lib/utils"

interface Props {
  accessibilityLabel?: string | undefined
  className?: string | undefined
  start: number
  stop: number
  step: number
  value: number
  onValueChange: (newValue: number) => void
  onPanStart?: (() => void) | undefined
  onPanStop?: (() => void) | undefined
  disabled?: boolean
  testID?: string | undefined
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value))
}

function alignToStep(value: number, min: number, step: number | undefined) {
  if (!step || step <= 0) return value
  return Math.round((value - min) / step) * step + min
}

export function Slider({
  accessibilityLabel: label,
  className,
  disabled,
  onPanStart,
  onPanStop,
  onValueChange,
  start,
  step,
  stop,
  testID,
  value,
}: Props) {
  const interactionActive = useRef(false)
  const primaryColor = useCSSVariable("--color-primary") as string

  const min = start
  const effectiveStep = step > 0 ? step : undefined
  const hasUsableRange = stop > start
  const max = hasUsableRange ? stop : start + (effectiveStep ?? 1)
  const effectiveDisabled = disabled || !hasUsableRange
  const sliderValue = hasUsableRange
    ? clamp(Number.isFinite(value) ? value : min, min, max)
    : min
  const iosSliderModifiers =
    Platform.OS === "ios"
      ? [
          tint(primaryColor),
          ...(label ? [swiftAccessibilityLabel(label)] : []),
          ...(effectiveDisabled ? [swiftDisabled(true)] : []),
        ]
      : []
  const androidSliderModifiers =
    Platform.OS === "android"
      ? [fillMaxWidth(), height(32), ...(testID ? [composeTestID(testID)] : [])]
      : []
  const hostProps = Platform.OS === "android" ? { seedColor: primaryColor } : {}

  const handleValueChange = (nextValue: number) => {
    if (!hasUsableRange) return
    const alignedValue = clamp(
      alignToStep(nextValue, min, effectiveStep),
      min,
      max,
    )
    onValueChange(alignedValue)
  }

  const handleTouchStart = () => {
    if (effectiveDisabled) return
    if (interactionActive.current) return
    interactionActive.current = true
    onPanStart?.()
  }

  const handleTouchEnd = () => {
    if (!interactionActive.current) return
    interactionActive.current = false
    onPanStop?.()
  }

  return (
    <View
      accessibilityLabel={label}
      accessibilityRole="adjustable"
      accessibilityState={{ disabled: effectiveDisabled }}
      className={cn(
        "relative min-h-8 justify-center",
        effectiveDisabled ? "opacity-50" : "opacity-100",
        className,
      )}
      onTouchCancel={handleTouchEnd}
      onTouchEnd={handleTouchEnd}
      onTouchStart={handleTouchStart}
    >
      <Host ignoreSafeArea="all" style={styles.host} {...hostProps}>
        {Platform.OS === "android" ? (
          <ComposeSlider
            value={sliderValue}
            min={min}
            max={max}
            onValueChange={handleValueChange}
            enabled={!effectiveDisabled}
            colors={{
              activeTrackColor: primaryColor,
              thumbColor: primaryColor,
            }}
            modifiers={androidSliderModifiers}
          />
        ) : (
          <SwiftSlider
            value={sliderValue}
            min={min}
            max={max}
            onValueChange={handleValueChange}
            modifiers={iosSliderModifiers}
            {...(testID ? { testID } : {})}
            {...(effectiveStep ? { step: effectiveStep } : {})}
          />
        )}
      </Host>
    </View>
  )
}

const styles = StyleSheet.create({
  host: {
    alignSelf: "stretch",
    height: 32,
    justifyContent: "center",
  },
})
