import { AppRegistry } from "react-native"

import {
  ANDROID_AUTO_SESSION_TASK,
  androidAutoSessionTask,
} from "./androidAutoSessionTask"

AppRegistry.registerHeadlessTask(
  ANDROID_AUTO_SESSION_TASK,
  () => androidAutoSessionTask,
)
