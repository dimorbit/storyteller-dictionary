export type NavigationMenuTab = "toc" | "bookmarks" | "highlights"

const fallbackTab: NavigationMenuTab = "toc"
let lastNavigationMenuTab: NavigationMenuTab = fallbackTab

function isNavigationMenuTab(value: string): value is NavigationMenuTab {
  return value === "toc" || value === "bookmarks" || value === "highlights"
}

export function getLastNavigationMenuTab() {
  return lastNavigationMenuTab
}

export function setLastNavigationMenuTab(tab: NavigationMenuTab) {
  lastNavigationMenuTab = tab
}

export function getNavigationMenuTabFromPathname(
  pathname: string,
): NavigationMenuTab | undefined {
  const tab = pathname.split("/").at(-1)

  return tab && isNavigationMenuTab(tab) ? tab : undefined
}
