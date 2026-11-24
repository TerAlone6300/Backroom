Third-Party Licenses and Attributions

This document provides licensing information, acknowledgements, and attribution for third-party works used as references or adapted in Backrooms.

Repository: https://github.com/TerAlone6300/Backroom

---

1. Project Licensing

Source Code

The source code of Backrooms is distributed under:

GNU General Public License v3.0 only (GPL-3.0-only).

See [LICENSE](LICENSE).

Third-Party Content

Third-party intellectual property remains subject to its respective original license.

The source-code license does not automatically replace or override the licenses of external creative works.

Content adapted from The Backrooms Wikidot is subject to the applicable Creative Commons licensing requirements.

Backrooms Wikidot Licensing

The Backrooms Wikidot generally publishes its contributions under:

Creative Commons Attribution-ShareAlike 3.0 (CC BY-SA 3.0).

Official references:

- "Licensing Guide" (https://backrooms-wiki.wikidot.com/licensing-guide)
- "Image Use Policy" (https://backrooms-wiki.wikidot.com/image-use-policy)
- "CC BY-SA 3.0 Legal Code" (https://creativecommons.org/licenses/by-sa/3.0/legalcode)

The Wikidot licensing guide explicitly permits game developers to use GPLv3 as an alternative to CC BY-SA 3.0, provided that the complete source code is publicly available in an immediately playable and modifiable form.

All applicable attribution and licensing requirements must still be respected.

---

2. Backrooms Wikidot - Level Credits

The following works are acknowledged as sources for the corresponding level concepts, themes, and adaptations implemented in Backrooms.

Unless otherwise stated, the original articles are licensed under CC BY-SA 3.0.

Level 0 - Threshold

Original concept:

- Original Backrooms concept: 4chan /x/ community.
- Adapted by Reddit user u/Deveyerr.
- Rewritten by DivineAtlas, Robert Goerman, and DrAkimoto.

Source:
https://backrooms-wiki.wikidot.com/level-0

License: CC BY-SA 3.0.

Level 1 - Habitable Zone

Authors:

- Praetor3005
- DivineAtlas

Additional historical contributions:

- Original concept: EnderMitten.
- Original Wikidot article: u/ThePizzaEater1000.
- Previous rewrite: etoisle.

Source:
https://backrooms-wiki.wikidot.com/level-1

License: CC BY-SA 3.0.

Level 2 - Abandoned Utility Halls

Credited author:

- Greggita Mahayfaio.

Additional historical and sectional contributions:

- Original author: u/M654z.
- Previous rewrite: 1000dumplings.
- Level 2 ownership and current contributions: Boring Talking X_X.
- Level 2.2 subsection: RestarttWrites.

Source:
https://backrooms-wiki.wikidot.com/level-2

License: CC BY-SA 3.0.

Level 3 - Electrical Station

Credited rewrite author:

- Natedagreat563.

Original concept:

- u/M654z.

Source:
https://backrooms-wiki.wikidot.com/level-3

License: CC BY-SA 3.0.

Level 4 - Abandoned Office

Credited original author:

- u/M654zy (Reddit).

The article is currently marked as undergoing a rewrite on the Wikidot.

Source:
https://backrooms-wiki.wikidot.com/level-4

License: CC BY-SA 3.0.

Level 5 - Terror Hotel

Credited author:

- Stretchsterz.

Additional contributions:

- Original author: Discord13.
- Ideas, code, and Entity 18 paragraph: ratscrapz.

Source:
https://backrooms-wiki.wikidot.com/level-5

License: CC BY-SA 3.0.

Level 6 - Lights Out

Author:

- Bart0nius.

Source:
https://backrooms-wiki.wikidot.com/level-6

License: CC BY-SA 3.0.

Level 11 - The City That Never Sleeps

Author:

- Greggita Mahayfaio.

Source:
https://backrooms-wiki.wikidot.com/level-11

License: CC BY-SA 3.0.

Level 37 - Sublimity

Author:

- egglord.

Additional contribution:

- Sariastuff provided critique and feedback.

Source:
https://backrooms-wiki.wikidot.com/level-37

License: CC BY-SA 3.0.

Note: The original article's images are separately credited and include CC BY-SA 4.0 material. Those image licenses must not be confused with the article's license.

Level 75 - Liminality

Author:

- Br Miller.

Additional contributors and critics:

- BlueSkiesAbove
- tosemjaz
- Epsilonsage
- Ka Sooty

Source:
https://backrooms-wiki.wikidot.com/level-75

License: CC BY-SA 3.0.

Level 139 - The Warrior's Trial

Author:

- Kitty Rika.

Source:
https://backrooms-wiki.wikidot.com/level-139

License: CC BY-SA 3.0.

Additional original article media contributors are acknowledged by the source page. The game's procedural island environment should not be represented as containing those original photographs unless they are actually included.

Unnumbered Level - The Grave

Author:

- CadaverCommander.

Additional contributions to the original article:

- etoisle: combination and editing of two images.
- Halflang: original Two-handed Highland sword image.
- Dannysee: landscape river image.

Source:
https://backrooms-wiki.wikidot.com/the-grave

License: CC BY-SA 3.0 for the article.

Individual media retain their own licenses, including CC BY-SA 3.0 and the Pixabay License.

---

3. Original and Reinterpreted Game Levels

The following implementations are not attributed to unrelated Wikidot articles solely because they share a level number.

Level 182 - City of Dusk

The game's implementation uses its own level name, environment design, and procedural generation.

The official Backrooms Wikidot Level 182 is titled "Aromatherapy", authored by Red-eyes Dragoon.

Source:
https://backrooms-wiki.wikidot.com/level-182

The two works are not interchangeable. This game implementation is not claimed to reproduce that article.

Level 201 - The Threshold

The game's implementation is titled "The Threshold".

The official Backrooms Wikidot Level 201 is titled "A E S T H E T I C", authored by mike14264.

Source:
https://backrooms-wiki.wikidot.com/level-201

The game's implementation is not claimed to reproduce that article.

---

4. Original Game Development

The following project components are developed specifically for Backrooms:

- Procedural level and maze generation.
- World generation and random world seeds.
- Level-specific geometry and environment generation.
- Custom wall, floor, ceiling, and lighting textures.
- Entity behavior and gameplay systems.
- Save and profile management.
- Multiplayer networking.
- Rendering systems and graphics backends.
- Android and desktop interfaces.
- Vulkan and OpenGL ES rendering integration.

Primary developer:

- TerAlone6300

Repository:
https://github.com/TerAlone6300/Backroom

The procedural environments and original implementation code are distinguished from the third-party concepts that inspired their design.

---

5. Software and Build Tools

The project currently declares the following build tools:

Component| Version| Purpose
Android Gradle Plugin| 8.5.2| Android build system
Kotlin Gradle Plugin| 2.0.21| Kotlin compilation
CMake| 3.22.1| Native C++ build system

These components are used as development and build tools.

They are not a complete inventory of software distributed with the application.

At the time of inspection, "app/build.gradle.kts" did not declare application-level Gradle dependencies.

Android platform APIs, Java standard libraries, and native system interfaces are not separately redistributed as bundled third-party libraries by this project.

Each build tool retains its own license and copyright notices.

---

6. Media and Asset Attribution

Original Project Assets

The following assets are original works created for this project by
TerAlone6300 and are licensed under GNU General Public License v3.0 only
(GPL-3.0-only):

- `assets/logo.png`
- `app/src/main/res/drawable-*/ic_launcher_logo.png`

---

7. Attribution Requirements

For every additional third-party work incorporated into the project, the following information should be recorded:

Field| Description
Work| Original work or asset title
Author| Original creator or contributors
Source| Direct URL to the original work
License| Exact license and version
Changes| Modifications or adaptations
Location| Repository path or in-game location

When distributing adapted Creative Commons content, the project must provide appropriate attribution, identify modifications, link to the source and license, and comply with the applicable ShareAlike requirements.

---

8. Disclaimer

Backrooms is an independent fan-made project inspired by the collaborative Backrooms universe.

The project does not claim ownership of third-party intellectual property.

All rights to third-party works remain with their respective authors and copyright holders.

This document is intended to provide transparent attribution and licensing information. It is not legal advice.

If an attribution is missing or incorrect, please contact the project maintainers through the repository's issue tracker.

---

Backrooms - Third-Party Licenses and Attributions

Maintained by TerAlone6300.

Repository: https://github.com/TerAlone6300/Backroom