# Makes hard query variants of one cover and checks the cover matcher still finds it.
# Usage: powershell -File tools/image_variants.ps1 build/bench/cover7.jpg 7
param([string]$Path, [long]$ExpectId, [string]$Base = "http://localhost:8080")
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile((Resolve-Path $Path))
$dir = Split-Path (Resolve-Path $Path)

function Save($bmp, $name) { $p = Join-Path $dir $name; $bmp.Save($p, [System.Drawing.Imaging.ImageFormat]::Jpeg); $bmp.Dispose(); $p }

$mir = New-Object System.Drawing.Bitmap $src; $mir.RotateFlip([System.Drawing.RotateFlipType]::RotateNoneFlipX)
$files = @{ "original" = (Resolve-Path $Path).Path; "mirrored" = (Save $mir "v_mirror.jpg") }

$w = $src.Width; $h = $src.Height
$crop = New-Object System.Drawing.Bitmap ([int]($w * 0.8)), ([int]($h * 0.8))
$g = [System.Drawing.Graphics]::FromImage($crop)
$g.DrawImage($src, (New-Object System.Drawing.Rectangle 0, 0, $crop.Width, $crop.Height), (New-Object System.Drawing.Rectangle ([int]($w * 0.1)), ([int]($h * 0.1)), $crop.Width, $crop.Height), [System.Drawing.GraphicsUnit]::Pixel); $g.Dispose()
$files["zoomed 80%"] = Save $crop "v_zoom.jpg"

# Cover pasted at the left of a wide dark "store page".
$wide = New-Object System.Drawing.Bitmap ($w * 3), $h
$g = [System.Drawing.Graphics]::FromImage($wide); $g.Clear([System.Drawing.Color]::FromArgb(24, 24, 32)); $g.DrawImage($src, 0, 0, $w, $h); $g.Dispose()
$files["in wide page"] = Save $wide "v_wide.jpg"
$src.Dispose()

foreach ($k in $files.Keys) {
  $r = curl.exe -s -F "file=@$($files[$k])" "$Base/api/search/image?scenes=false" | ConvertFrom-Json
  $ids = @($r.covers | ForEach-Object { [long]$_.media.id })
  $rank = [array]::IndexOf($ids, $ExpectId)
  $all = ($r.covers | ForEach-Object { "$($_.media.title)/$($_.distance)" }) -join "; "
  "{0,-14} expected rank={1}   [{2}]" -f $k, ($(if ($rank -ge 0) { $rank + 1 } else { "miss" })), $all
}
