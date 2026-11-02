import { Host } from "@expo/ui"
import { ProgressView } from "@expo/ui/swift-ui"
import {
  accessibilityLabel as swiftAccessibilityLabel,
  progressViewStyle,
  tint,
} from "@expo/ui/swift-ui/modifiers"
import { Platform, View } from "react-native"
import { useCSSVariable } from "uniwind"

import { cn } from "@/lib/utils"

import { Slider } from "./ui/slider"

type Props = {
  accessibilityLabel?: string | undefined
  className?: string | undefined
  start?: number
  step?: number
  stop?: number
  progress: number
  onProgressChange?: ((newProgress: number) => void) | undefined
  onPanStart?: (() => void) | undefined
  onPanStop?: (() => void) | undefined
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value))
}

function ReadOnlyProgressBar({
  accessibilityLabel,
  className,
  progress,
  start,
  stop,
}: Required<Pick<Props, "progress" | "start" | "stop">> &
  Pick<Props, "accessibilityLabel" | "className">) {
  const primaryColor = useCSSVariable("--color-primary") as string
  const hasUsableRange = stop > start
  const boundedProgress = hasUsableRange ? clamp(progress, start, stop) : start
  const progressValue = hasUsableRange
    ? (boundedProgress - start) / (stop - start)
    : 0

  const progressBarProps = {
    accessibilityLabel,
    accessibilityRole: "progressbar" as const,
    accessibilityValue: hasUsableRange
      ? { min: start, max: stop, now: boundedProgress }
      : undefined,
  }

  if (Platform.OS === "android") {
    return (
      <View
        {...progressBarProps}
        className={cn("relative min-h-8 justify-center", className)}
      >
        <View className="bg-secondary h-4 rounded-xl">
          <View
            className="bg-primary h-full rounded-xl"
            style={{ width: `${progressValue * 100}%` }}
          />
        </View>
      </View>
    )
  }

  return (
    <View
      {...progressBarProps}
      className={cn("relative min-h-8 justify-center", className)}
    >
      <Host ignoreSafeArea="all" className="self-stretch">
        <ProgressView
          value={progressValue}
          modifiers={[
            tint(primaryColor),
            progressViewStyle("linear"),
            ...(accessibilityLabel
              ? [swiftAccessibilityLabel(accessibilityLabel)]
              : []),
          ]}
        />
      </Host>
    </View>
  )
}

export function ProgressBar({
  accessibilityLabel,
  className,
  start = 0,
  step = 1,
  stop = 100,
  progress,
  onProgressChange,
  onPanStart,
  onPanStop,
}: Props) {
  if (onProgressChange) {
    return (
      <View {...(className && { className })}>
        <Slider
          accessibilityLabel={accessibilityLabel}
          start={start}
          stop={stop}
          step={step}
          value={progress}
          onValueChange={onProgressChange}
          onPanStart={onPanStart}
          onPanStop={onPanStop}
        />
      </View>
    )
  }

  return (
    <ReadOnlyProgressBar
      accessibilityLabel={accessibilityLabel}
      className={className}
      progress={progress}
      start={start}
      stop={stop}
    />
  )
}
