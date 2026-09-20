import { useRouter } from "expo-router"
import { ChevronLeft } from "lucide-react-native"
import { TouchableOpacity } from "react-native"

import { Icon } from "./ui/icon"

interface Props {
  fallback?: string
}

export function BackButton({ fallback = "/" }: Props) {
  const router = useRouter()

  return (
    <TouchableOpacity
      className="size-11 items-center justify-center"
      accessibilityRole="button"
      accessibilityLabel="Go back"
      onPress={() => {
        if (router.canGoBack()) {
          router.back()
        } else {
          router.replace(fallback)
        }
      }}
    >
      <Icon as={ChevronLeft} size={24} />
    </TouchableOpacity>
  )
}
