import { Pause, Play } from "lucide-react-native"
import { ActivityIndicator } from "react-native"

import { useColorTheme } from "@/hooks/useColorTheme"
import { Storyteller } from "@/modules/readium"
import { useAppSelector } from "@/store/appState"
import {
  getIsAudioLoading,
  getIsPlaying,
} from "@/store/selectors/bookshelfSelectors"

import { Button, type ButtonProps } from "./ui/button"
import { Icon } from "./ui/icon"

type Props = {
  size?: number | undefined
  automaticRewind?: boolean
  buttonSize?: ButtonProps["size"]
}

export function PlayPause({
  size = 24,
  automaticRewind = true,
  buttonSize = "icon",
}: Props) {
  const isPlaying = useAppSelector(getIsPlaying)
  const isLoading = useAppSelector(getIsAudioLoading)
  const { foreground } = useColorTheme()

  if (isLoading) {
    return (
      <ActivityIndicator
        accessibilityLabel="Loading audio"
        accessibilityRole="progressbar"
        size={size}
      />
    )
  }

  return isPlaying ? (
    <Button
      accessibilityLabel="Pause"
      variant="ghost"
      size={buttonSize}
      onPress={() => {
        Storyteller.pause()
      }}
    >
      <Icon as={Pause} fill={foreground} size={size} />
    </Button>
  ) : (
    <Button
      accessibilityLabel="Play"
      variant="ghost"
      size={buttonSize}
      onPress={() => {
        Storyteller.play(automaticRewind)
      }}
    >
      <Icon
        as={Play}
        fill={foreground}
        className="bg-transparent"
        size={size}
      />
    </Button>
  )
}
