<p align="center">
  <img src="learning/assets/android-splash-sequence.png" alt="A vermilion seal stamps the name Aniga onto a black screen" width="100%">
</p>

<h1 align="center">Aniga</h1>

<p align="center">
  <strong>Tonight's pull list, printed in ink.</strong><br>
  A shelf for anime, manga, manhwa, and manhua that learns what you would actually finish.
</p>

<p align="center">
  <img src="learning/assets/android-home.png" alt="The shelf opens on a manga-page spread of tonight's three picks" width="320">
</p>

<p align="center">
  Open the app and the first thing you see is a page, not a feed.<br>
  Three covers, laid out like a printed spread, with a reason written under the one it thinks you will love.
</p>

<br>

<table>
  <tr>
    <td align="center" width="33%" valign="top">
      <img src="learning/assets/android-foryou.png" alt="For You shows which genres pull you in and which push you away" width="230"><br><br>
      <strong>It tells you why</strong><br>
      Drama pulls you in. Tournaments push you away. The list under that is ranked from your ratings, and every row says so in a sentence.
    </td>
    <td align="center" width="33%" valign="top">
      <img src="learning/assets/android-discover.png" alt="Discover search results for Chainsaw Man, cover after cover" width="230"><br><br>
      <strong>Then you go looking</strong><br>
      Type a title, describe the story, or bring a picture. The chapter is one tap from the cover.
    </td>
    <td align="center" width="33%" valign="top">
      <img src="learning/assets/android-detail.png" alt="A title page for Grandmaster of Demonic Cultivation, with the first chapters ready" width="230"><br><br>
      <strong>And you stay</strong><br>
      The cover, the story, and the chapter list live on one quiet page, tinted by the art itself.
    </td>
  </tr>
</table>

<br>

## Read it the way paper feels

<p align="center">
  <img src="learning/assets/android-reader-sepia.png" alt="A chapter open in warm sepia, with night, paper, and black one tap away" width="320">
</p>

<p align="center">
  Night ink. Sepia. Paper. True black.<br>
  Scroll, and the chrome gets out of the way. Reach the end, and the shelf remembers where you stopped.
</p>

<p align="center">
  <img src="learning/assets/android-reader-end.png" alt="The end of a chapter, with a small ball waiting and a button for the next one" width="320">
</p>

<br>

## The same shelf, on a wider page

<p align="center">
  <img src="learning/assets/web-shelf.png" alt="The browser shelf, with the same manga-page hero and a row of covers underneath" width="100%">
</p>

<p align="center">
  Phone or browser, it is one shelf. Same covers. Same reasons. Same page you were in the middle of.
</p>

<p align="center">
  <a href="learning/README.md"><strong>Walk through every screen →</strong></a>
</p>

<br>

## Open it

The console runs on your machine.

```powershell
cd infra
copy .env.example .env
docker compose up -d --build
```

Then open [http://localhost:8080](http://localhost:8080).

The phone build reads its address from `aniga.local.properties` (there is an example beside it). With nothing set, it looks for the console on the emulator.
