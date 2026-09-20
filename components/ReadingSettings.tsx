import { skipToken } from "@reduxjs/toolkit/query"
import deepmerge from "deepmerge"
import { dequal } from "dequal"
import { Link } from "expo-router"
import { useMemo } from "react"
import { Pressable, StyleSheet, View } from "react-native"

import { Switch } from "@/components/ui/switch"
import {
  type ReadaloudDecoratorStyle,
  defaultPreferences,
} from "@/database/preferencesTypes"
import { formatNumber } from "@/formatting"
import { cn } from "@/lib/utils"
import {
  useGetBookPreferencesQuery,
  useGetGlobalPreferencesQuery,
  useSetBookPreferencesAsDefaultsMutation,
  useUpdateBookPreferenceMutation,
  useUpdateGlobalPreferenceMutation,
} from "@/store/localApi"
import { type UUID } from "@/uuid"

import { ColorPickerDialog } from "./ColorPickerDialog"
import { LoadingView } from "./LoadingView"
import { ButtonGroup, ButtonGroupButton } from "./ui/ButtonGroup"
import { Button } from "./ui/button"
import { Select, SelectItem, SelectTrigger, SelectValue } from "./ui/select"
import { Slider } from "./ui/slider"
import { Text } from "./ui/text"
import { colors } from "./ui/tokens/colors"
import { fontSizes } from "./ui/tokens/fontSizes"
import { spacing } from "./ui/tokens/spacing"

type Props = {
  bookUuid?: UUID
}

function getFontPreviewFamily(fontFamily: string) {
  switch (fontFamily) {
    case "Literata":
      return "Literata_500Medium"
    case "OpenDyslexic":
      return "OpenDyslexic-Regular"
    default:
      return fontFamily
  }
}

export function ReadingSettings({ bookUuid }: Props) {
  const { data: globalPreferences } = useGetGlobalPreferencesQuery()

  const { data: bookPreferences } = useGetBookPreferencesQuery(
    bookUuid ? { uuid: bookUuid } : skipToken,
  )

  const [updateGlobalPreference] = useUpdateGlobalPreferenceMutation()
  const [updateBookPreference] = useUpdateBookPreferenceMutation()
  const [setBookPreferencesAsDefaults] =
    useSetBookPreferencesAsDefaultsMutation()

  const preferences = useMemo(
    () =>
      bookPreferences
        ? globalPreferences && deepmerge(globalPreferences, bookPreferences)
        : globalPreferences,
    [globalPreferences, bookPreferences],
  )

  const dirty = useMemo(
    () => dequal(globalPreferences, preferences),
    [globalPreferences, preferences],
  )

  const typographyPreferencesAreDefaults = useMemo(
    () => dequal(preferences?.typography, defaultPreferences.typography),
    [preferences?.typography],
  )

  if (!preferences) return <LoadingView />

  const readaloudDecoratorStyle =
    preferences.readaloudDecoratorStyle ??
    defaultPreferences.readaloudDecoratorStyle

  return (
    <View className="mt-8">
      <Text variant="h2">Appearance</Text>
      <View className="my-3 w-full flex-row items-center justify-between">
        <Text maxFontSizeMultiplier={1} className="text-lg">
          Dark mode
        </Text>
        <ButtonGroup
          value={preferences.darkMode}
          onChange={(value: boolean | "auto") => {
            updateGlobalPreference({ name: "darkMode", value })
          }}
        >
          <ButtonGroupButton accessibilityLabel="Use light mode" value={false}>
            <Text maxFontSizeMultiplier={1}>Light</Text>
          </ButtonGroupButton>
          <ButtonGroupButton
            accessibilityLabel="Use device dark mode setting"
            value="auto"
          >
            <Text maxFontSizeMultiplier={1}>Device</Text>
          </ButtonGroupButton>
          <ButtonGroupButton accessibilityLabel="Use dark mode" value={true}>
            <Text maxFontSizeMultiplier={1}>Dark</Text>
          </ButtonGroupButton>
        </ButtonGroup>
      </View>
      <View className="my-3 w-full flex-row items-center justify-between">
        <Text className="text-lg">Light theme</Text>
        <Select
          value={{
            value: preferences.lightTheme,
            label: preferences.lightTheme,
          }}
          onValueChange={(option) => {
            if (!option) return
            updateGlobalPreference({ name: "lightTheme", value: option.value })
          }}
        >
          <SelectTrigger
            accessibilityLabel={`Light theme, ${preferences.lightTheme}`}
          >
            <SelectValue placeholder="" />
          </SelectTrigger>
          {preferences.colorThemes
            .filter(({ isDark }) => !isDark)
            .map(({ name }) => (
              <SelectItem key={name} label={name} value={name} />
            ))}
        </Select>
      </View>
      <View className="my-3 w-full flex-row items-center justify-between">
        <Text className="text-lg">Dark theme</Text>
        <Select
          value={{
            value: preferences.darkTheme,
            label: preferences.darkTheme,
          }}
          onValueChange={(option) => {
            if (!option) return
            updateGlobalPreference({ name: "darkTheme", value: option.value })
          }}
        >
          <SelectTrigger
            accessibilityLabel={`Dark theme, ${preferences.darkTheme}`}
          >
            <SelectValue placeholder="" />
          </SelectTrigger>
          {preferences.colorThemes
            .filter(({ isDark }) => isDark)
            .map(({ name }) => (
              <SelectItem key={name} label={name} value={name} />
            ))}
        </Select>
      </View>
      <Link href="/custom-theme" asChild>
        <Button
          accessibilityLabel="Manage custom themes"
          size="flex"
          variant="ghost"
        >
          <Text className="text-primary group-active:text-primary/80">
            Manage custom themes
          </Text>
        </Button>
      </Link>
      <View className="my-3 w-full gap-3">
        <View className="w-full flex-row flex-wrap items-center justify-between gap-3">
          <Text maxFontSizeMultiplier={1.5} className="text-lg">
            Readaloud decoration
          </Text>
          <ButtonGroup
            value={readaloudDecoratorStyle}
            onChange={(value: ReadaloudDecoratorStyle) => {
              if (bookUuid) {
                updateBookPreference({
                  bookUuid,
                  name: "readaloudDecoratorStyle",
                  value,
                })
              } else {
                updateGlobalPreference({
                  name: "readaloudDecoratorStyle",
                  value,
                })
              }
            }}
          >
            <ButtonGroupButton
              accessibilityLabel="Use highlight readaloud decoration"
              size="sm"
              value="highlight"
            >
              <Text maxFontSizeMultiplier={1.5}>Highlight</Text>
            </ButtonGroupButton>
            <ButtonGroupButton
              accessibilityLabel="Use underline readaloud decoration"
              size="sm"
              value="underline"
            >
              <Text maxFontSizeMultiplier={1.5}>Underline</Text>
            </ButtonGroupButton>
          </ButtonGroup>
        </View>
        {readaloudDecoratorStyle === "highlight" && (
          <View className="w-full flex-row items-center justify-between gap-4">
            <Text maxFontSizeMultiplier={1.5} className="text-lg">
              Highlight color
            </Text>
            <ColorPickerDialog
              key={preferences.readaloudColor}
              initialValue={preferences.readaloudColor}
              onSave={(value) => {
                if (bookUuid) {
                  updateBookPreference({
                    bookUuid,
                    name: "readaloudColor",
                    value,
                  })
                } else {
                  updateGlobalPreference({ name: "readaloudColor", value })
                }
              }}
            />
          </View>
        )}
      </View>
      <View className="my-3 w-full flex-row items-center gap-10">
        <Text className="text-lg">Floating toolbars</Text>
        <Switch
          checked={preferences.floatingToolbar}
          onCheckedChange={(value) =>
            updateGlobalPreference({
              name: "floatingToolbar",
              value,
            })
          }
        />
      </View>
      <Text variant="h2" className="mt-4">
        Margins
      </Text>
      <View className="my-3 w-full gap-2">
        <View className="w-full flex-row items-center justify-between gap-4">
          <Text maxFontSizeMultiplier={1} className="text-lg">
            Left margin
          </Text>
          <Text maxFontSizeMultiplier={1} className="shrink-0 text-sm">
            {preferences.layout.marginLeft ?? 0}px
          </Text>
        </View>
        <Slider
          accessibilityLabel="Left margin"
          className="w-full"
          start={0}
          stop={50}
          step={1}
          value={preferences.layout.marginLeft ?? 0}
          onValueChange={(value) => {
            const update = {
              ...preferences.layout,
              marginLeft: Math.round(value),
            }
            if (bookUuid) {
              updateBookPreference({
                bookUuid,
                name: "layout",
                value: update,
              })
            } else {
              updateGlobalPreference({ name: "layout", value: update })
            }
          }}
        />
      </View>
      <View className="my-3 w-full gap-2">
        <View className="w-full flex-row items-center justify-between gap-4">
          <Text maxFontSizeMultiplier={1} className="text-lg">
            Right margin
          </Text>
          <Text maxFontSizeMultiplier={1} className="shrink-0 text-sm">
            {preferences.layout.marginRight ?? 0}px
          </Text>
        </View>
        <Slider
          accessibilityLabel="Right margin"
          className="w-full"
          start={0}
          stop={50}
          step={1}
          value={preferences.layout.marginRight ?? 0}
          onValueChange={(value) => {
            const update = {
              ...preferences.layout,
              marginRight: Math.round(value),
            }
            if (bookUuid) {
              updateBookPreference({
                bookUuid,
                name: "layout",
                value: update,
              })
            } else {
              updateGlobalPreference({ name: "layout", value: update })
            }
          }}
        />
      </View>

      <View>
        <Text variant="h2">Layout</Text>
        <View style={styles.field}>
          <Text maxFontSizeMultiplier={1} className="text-lg">
            Reader layout
          </Text>
          <ButtonGroup
            value={preferences.layout.scroll ? "scroll" : "page"}
            onChange={(layout: "page" | "scroll") => {
              const update = {
                ...preferences.layout,
                scroll: layout === "scroll",
              }
              if (bookUuid) {
                updateBookPreference({
                  bookUuid,
                  name: "layout",
                  value: update,
                })
              } else {
                updateGlobalPreference({ name: "layout", value: update })
              }
            }}
          >
            <ButtonGroupButton
              accessibilityLabel="Use page layout"
              value="page"
            >
              <Text maxFontSizeMultiplier={1}>Page</Text>
            </ButtonGroupButton>
            <ButtonGroupButton
              accessibilityLabel="Use scroll layout"
              value="scroll"
            >
              <Text maxFontSizeMultiplier={1}>Scroll</Text>
            </ButtonGroupButton>
          </ButtonGroup>
        </View>
      </View>
      <View>
        <Text variant="h2">Typography{!bookUuid && " defaults"}</Text>
        {bookUuid ? (
          <View style={styles.typographyControls}>
            <Button
              accessibilityLabel="Set typography as defaults"
              disabled={dirty}
              variant="ghost"
              size="sm"
              onPress={() => {
                setBookPreferencesAsDefaults({ bookUuid })
              }}
            >
              <Text
                maxFontSizeMultiplier={1.5}
                className={dirty ? "opacity-50" : "text-primary"}
              >
                Set as defaults
              </Text>
            </Button>
            <Button
              accessibilityLabel="Reset typography to defaults"
              variant="ghost"
              size="sm"
              disabled={typographyPreferencesAreDefaults}
              onPress={() => {
                updateBookPreference({
                  bookUuid,
                  name: "typography",
                  value: {},
                })
              }}
            >
              <Text
                maxFontSizeMultiplier={1.5}
                className={
                  typographyPreferencesAreDefaults
                    ? "opacity-50"
                    : "text-primary"
                }
              >
                Reset to defaults
              </Text>
            </Button>
          </View>
        ) : (
          <Pressable
            accessibilityLabel="Reset typography settings"
            accessibilityRole="button"
            accessibilityState={{ disabled: typographyPreferencesAreDefaults }}
            disabled={typographyPreferencesAreDefaults}
            onPress={() => {
              updateGlobalPreference({
                name: "typography",
                value: defaultPreferences.typography,
              })
            }}
          >
            <Text
              className={cn(
                "my-2 self-end",
                typographyPreferencesAreDefaults
                  ? "opacity-50"
                  : "text-primary",
              )}
            >
              Reset
            </Text>
          </Pressable>
        )}
      </View>

      <View className="my-3 w-full gap-2">
        <View className="w-full flex-row items-center justify-between gap-4">
          <Text maxFontSizeMultiplier={1} className="text-lg">
            Font scaling
          </Text>
          <Text className="shrink-0 text-sm" maxFontSizeMultiplier={1}>
            {formatNumber(preferences.typography.scale, 2)}x
          </Text>
        </View>
        <Slider
          accessibilityLabel="Font scaling"
          className="w-full"
          start={0.7}
          stop={2}
          step={0.05}
          value={preferences.typography.scale}
          onValueChange={(value) => {
            const update = {
              ...preferences.typography,
              // Rounding to hundredths to account for floating point errors
              scale: Math.round(value * 100) / 100,
            }
            if (bookUuid) {
              updateBookPreference({
                bookUuid,
                name: "typography",
                value: update,
              })
            } else {
              updateGlobalPreference({ name: "typography", value: update })
            }
          }}
        />
      </View>
      <View className="my-3 w-full gap-2">
        <View className="w-full flex-row items-center justify-between gap-4">
          <Text maxFontSizeMultiplier={1} className="text-lg">
            Line height
          </Text>
          <Text maxFontSizeMultiplier={1} className="shrink-0 text-sm">
            {formatNumber(preferences.typography.lineHeight, 2)}x
          </Text>
        </View>
        <Slider
          accessibilityLabel="Line height"
          className="w-full"
          start={1.0}
          stop={2.0}
          step={0.05}
          value={preferences.typography.lineHeight}
          onValueChange={(value) => {
            const update = {
              ...preferences.typography,
              // Rounding to hundredths to account for floating point errors
              lineHeight: Math.round(value * 100) / 100,
            }
            if (bookUuid) {
              updateBookPreference({
                bookUuid,
                name: "typography",
                value: update,
              })
            } else {
              updateGlobalPreference({ name: "typography", value: update })
            }
          }}
        />
      </View>
      <View style={styles.field}>
        <Text maxFontSizeMultiplier={1} className="text-lg">
          Text alignment
        </Text>
        <ButtonGroup
          value={preferences.typography.alignment}
          onChange={(value: "justify" | "left") => {
            const update = {
              ...preferences.typography,
              alignment: value,
            }
            if (bookUuid) {
              updateBookPreference({
                bookUuid,
                name: "typography",
                value: update,
              })
            } else {
              updateGlobalPreference({ name: "typography", value: update })
            }
          }}
        >
          <ButtonGroupButton
            accessibilityLabel="Justify text alignment"
            value="justify"
          >
            <Text maxFontSizeMultiplier={1}>Justify</Text>
          </ButtonGroupButton>
          <ButtonGroupButton
            accessibilityLabel="Use left text alignment"
            value="left"
          >
            <Text maxFontSizeMultiplier={1}>Left</Text>
          </ButtonGroupButton>
        </ButtonGroup>
      </View>
      <View style={styles.field}>
        <Text maxFontSizeMultiplier={1.25} className="text-lg">
          Font family
        </Text>
        <Select
          value={{
            value: preferences.typography.fontFamily,
            label: preferences.typography.fontFamily,
          }}
          onValueChange={(option) => {
            if (!option) return
            const update = {
              ...preferences.typography,
              fontFamily: option.value,
            }
            if (bookUuid) {
              updateBookPreference({
                bookUuid,
                name: "typography",
                value: update,
              })
            } else {
              updateGlobalPreference({ name: "typography", value: update })
            }
          }}
        >
          <SelectTrigger
            accessibilityLabel={`Font family, ${preferences.typography.fontFamily}`}
          >
            <SelectValue
              placeholder=""
              style={{
                fontFamily: getFontPreviewFamily(
                  preferences.typography.fontFamily,
                ),
              }}
            />
          </SelectTrigger>
          <SelectItem label="Literata" value="Literata" />
          <SelectItem label="OpenDyslexic" value="OpenDyslexic" />
          {preferences.customFonts.map(({ name }) => (
            <SelectItem key={name} label={name} value={name} />
          ))}
        </Select>
      </View>
      <Link href="custom-fonts" asChild>
        <Button
          accessibilityLabel="Manage custom fonts"
          size="flex"
          variant="ghost"
        >
          <Text className="text-primary group-active:text-primary/80">
            Manage custom fonts
          </Text>
        </Button>
      </Link>
    </View>
  )
}

const styles = StyleSheet.create({
  subsubheading: {
    ...fontSizes.xl,
    fontWeight: "600",
    marginVertical: spacing["1.5"],
  },
  subheading: {
    ...fontSizes["2xl"],
    fontWeight: "bold",
    marginVertical: spacing["1.5"],
  },
  explanation: {
    ...fontSizes.xs,
  },
  field: {
    width: "100%",
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginVertical: spacing["1.5"],
  },
  label: {
    ...fontSizes.lg,
    flexGrow: 0,
  },
  typographyHeaderContainer: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    flexWrap: "wrap",
  },
  bookTypographyHeaderContainer: {},
  typographyControls: { flexDirection: "row", justifyContent: "space-between" },
  pressable: {
    color: colors.primary9,
  },
  disabled: {
    opacity: 0.6,
  },
})
