package zed.rainxch.githubstore.util

fun formatRecency(days: Int): String = when (days) {
    0 -> "Released today"
    1 -> "Released yesterday"
    else -> "Released $days days ago"
}
