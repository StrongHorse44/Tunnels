package io.github.stronghorse44.tunnels.unzip

import androidx.core.content.FileProvider

/** Its own class so it can't clash with another module's FileProvider in the merged manifest. */
class UnzipFileProvider : FileProvider(R.xml.unzip_file_paths)
