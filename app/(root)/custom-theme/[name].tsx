import { useLocalSearchParams, useRouter } from "expo-router"

import { CustomThemeEditor } from "@/components/CustomThemeEditor"
import {
  useGetGlobalPreferencesQuery,
  useUpdateGlobalPreferenceMutation,
} from "@/store/localApi"

export default function EditCustomThemeScreen() {
  const { name } = useLocalSearchParams<{ name: string }>()

  const router = useRouter()

  const { data: preferences } = useGetGlobalPreferencesQuery()
  const [updatePreference] = useUpdateGlobalPreferenceMutation()

  const initialTheme = preferences?.colorThemes.find(
    (theme) => theme.name === name,
  )

  if (!initialTheme) return null

  return (
    <CustomThemeEditor
      initialTheme={initialTheme}
      existingNames={(preferences?.colorThemes ?? [])
        .filter((theme) => theme.name !== name)
        .map((theme) => theme.name)}
      onSave={(updated) => {
        const oldName = name
        const updatedThemes = [...(preferences?.colorThemes ?? [])]

        const updatedIndex = updatedThemes.findIndex((t) => t.name === oldName)
        if (updatedIndex !== -1) {
          updatedThemes.splice(updatedIndex, 1, updated)
        }

        updatePreference({
          name: "colorThemes",
          value: updatedThemes,
        })

        if (updated.name !== oldName) {
          if (preferences?.lightTheme === oldName) {
            updatePreference({ name: "lightTheme", value: updated.name })
          }
          if (preferences?.darkTheme === oldName) {
            updatePreference({ name: "darkTheme", value: updated.name })
          }
        }

        router.back()
      }}
    />
  )
}
