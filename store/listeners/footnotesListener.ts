import { router } from "expo-router"

import { bookshelfSlice } from "@/store/slices/bookshelfSlice"

import { startAppListening } from "./listenerMiddleware"

startAppListening({
  actionCreator: bookshelfSlice.actions.footnoteOpened,
  effect: () => {
    router.push("/footnote")
  },
})
