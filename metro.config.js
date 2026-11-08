const { getSentryExpoConfig } = require("@sentry/react-native/metro")
const { getDefaultConfig } = require("expo/metro-config")
const { withUniwindConfig } = require("uniwind/metro")

const config = process.env.EXPO_PUBLIC_ENABLE_SENTRY
  ? getSentryExpoConfig(__dirname)
  : getDefaultConfig(__dirname)

// Hermes in this React Native version cannot parse dynamic import(), and kysely's
// ESM build uses one in FileMigrationProvider. Resolve kysely to its CommonJS
// build, which uses require() instead.
const path = require("path")
const defaultResolveRequest = config.resolver.resolveRequest
config.resolver.resolveRequest = (context, moduleName, platform) => {
  if (moduleName === "kysely") {
    return {
      type: "sourceFile",
      filePath: path.join(__dirname, "node_modules/kysely/dist/cjs/index.js"),
    }
  }

  return (defaultResolveRequest ?? context.resolveRequest)(
    context,
    moduleName,
    platform,
  )
}

module.exports = withUniwindConfig(config, {
  cssEntryFile: "./global.css",
})
