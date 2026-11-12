| Thumb | Keyboard | Words wrong | of them left wrong | of them spoiled | Slips mended | Right words spoiled per 1,000 | Sentences all right |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| careful | Ghost Keyboard | **2.7%** (2.0 to 3.5) | 2.5% (43) | 0.2% (4) | 85% of 281 | 2.8 (0.7 to 5.6) | 86% |
| careful | Gboard | **1.8%** (1.2 to 2.5) | 1.6% (27) | 0.3% (5) | 90% of 282 | 3.4 (0.7 to 6.9) | 90% |
| leaning | Ghost Keyboard | **7.7%** (6.4 to 9.1) | 7.4% (129) | 0.3% (5) | 81% of 684 | 4.8 (1.0 to 9.3) | 66% |
| leaning | Gboard | **3.2%** (2.3 to 4.1) | 2.9% (50) | 0.3% (6) | 93% of 690 | 5.7 (1.9 to 10.2) | 85% |
| sloppy | Ghost Keyboard | **16.1%** (14.3 to 18.0) | 16.1% (279) | 0.1% (1) | 72% of 1000 | 1.4 (0.0 to 4.3) | 41% |
| sloppy | Gboard | **6.4%** (5.1 to 7.7) | 6.2% (108) | 0.2% (3) | 89% of 1003 | 4.1 (0.0 to 9.6) | 71% |

| Thumb | Ghost Keyboard wrong less the other's (points) | 95% interval | left wrong | spoiled |
| --- | ---: | ---: | ---: | ---: |
| careful, against Gboard | +0.9 | +0.1 to +1.6 | +0.9 (+0.3 to +1.5) | -0.1 (-0.4 to +0.3) |
| leaning, against Gboard | +4.5 | +3.1 to +5.8 | +4.6 (+3.2 to +5.9) | -0.1 (-0.4 to +0.2) |
| sloppy, against Gboard | +9.7 | +8.0 to +11.5 | +9.9 (+8.2 to +11.6) | -0.1 (-0.3 to +0.0) |

| Thumb | Keyboard | Taps that landed on another letter's key | of them typed as the letter meant, there and then | Taps on their own key | of them typed as something else | Words wrong where no tap was moved off a key that is no letter |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| careful | Ghost Keyboard | 224 | 0 (0%) | 6969 | 0 (0.0%) | 2.7% of 1681 |
| careful | Gboard | 217 | 130 (60%) | 6976 | 8 (0.1%) | 1.9% of 1681 |
| leaning | Ghost Keyboard | 807 | 0 (0%) | 6357 | 0 (0.0%) | 8.0% of 1550 |
| leaning | Gboard | 820 | 545 (66%) | 6349 | 18 (0.3%) | 3.3% of 1550 |
| sloppy | Ghost Keyboard | 1371 | 2 (0%) | 5784 | 1 (0.0%) | 15.6% of 1405 |
| sloppy | Gboard | 1346 | 736 (55%) | 5814 | 47 (0.8%) | 6.2% of 1405 |

Ghost Keyboard's decoder on the JVM, fed the same taps: the field's text is the same, character for character, in 900 of 900 sentences (100.0%); 5205 of 5205 words come out the same (100.00%).

| What went wrong (Ghost Keyboard wrong, Gboard right) | Words | careful | leaning | sloppy | Left as typed | The slip spelled another word | The word meant is in no list of ours |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| a short word (three letters or fewer) | 85 | 1 | 29 | 55 | 60 | 39 | 0 |
| the space: run into its neighbour, or split | 75 | 2 | 21 | 52 | 5 | 7 | 1 |
| three or more keys off | 39 | 1 | 7 | 31 | 28 | 0 | 3 |
| one key off | 32 | 7 | 9 | 16 | 24 | 9 | 8 |
| a letter left out | 28 | 8 | 10 | 10 | 21 | 12 | 0 |
| two keys off | 24 | 1 | 6 | 17 | 23 | 0 | 5 |
| a letter swapped | 15 | 1 | 9 | 5 | 14 | 4 | 0 |
| a letter doubled | 8 | 2 | 1 | 5 | 7 | 0 | 0 |
| a name or other capital | 8 | 0 | 4 | 4 | 7 | 4 | 2 |
| a right word changed | 6 | 4 | 2 | 0 | 0 | 0 | 5 |
| a contraction | 6 | 0 | 3 | 3 | 3 | 1 | 0 |
| all | 326 | 27 | 101 | 198 | 192 | 76 | 24 |

| What stood in the decoder's way (the same 326 words) | Words | careful | leaning | sloppy | Ended by a mark from ?123, so read without taps | For example (typed, left, meant) |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| more letters off their keys than the search will change in a word this long | 89 | 1 | 25 | 63 | 2 | ur, ur, it; jluze, jluze, house; jr, jr, he; vry, very, get |
| no word typed, the word meant within the search's reach, and not mended | 65 | 4 | 19 | 42 | 18 | sgsrtet, sgsrtet, starter; courteoya, courteoya, courteous; ull, all, i'll; prayunf, prayunf, praying |
| a letter left out, doubled or swapped | 51 | 11 | 20 | 20 | 11 | bespke, bespke, bespoke; vputreous, vputreous, courteous; cat, cat, can't; ak, ak, all |
| cut in two: one of its letters landed on the space bar | 37 | 2 | 13 | 22 | 1 | way hrd, he'd, watched; tragi, tragi, tragic; ac ident, don't, accident; bathi g, g, bathing |
| the letters typed spell a common word, which is kept | 31 | 4 | 8 | 19 | 6 | lime, lime, like; mr, mr, me; id, i'd, is; di, di, do |
| run together: the space landed on a letter | 26 | 0 | 2 | 24 | 4 | drpartmeny, otherbdrpartmeny, department; kerdb, (nothing), need; nww, kerdbnww, new; in, (nothing), i |
| run together: no space was typed | 12 | 0 | 6 | 6 | 0 | s, (nothing), a; conmected, (nothing), connected; yrt, conmectedyrt, yet; my, (nothing), my |
| the letters typed spell a rare word | 9 | 1 | 6 | 2 | 0 | yom, yom, tom; zo, zo, so; kn, kn, in; dior, dior, door |
| typed as meant, and changed | 6 | 4 | 2 | 0 | 0 | yurt, hurt, yurt; berber, better, berber; dialled, dialed, dialled; envies, envied, envies |
| all | 326 | 27 | 101 | 198 | 42 | |

The other way, 64 words Ghost Keyboard got and Gboard did not: a short word (three letters or fewer) 19; one key off 12; a right word changed 11; the space: run into its neighbour, or split 7; a letter swapped 4; a name or other capital 4; a letter doubled 3; a letter left out 2; two keys off 1; a contraction 1.

Not in the score, which leaves capitals aside: of the names the thumb typed in lower case and the field has the right letters of (Tom, Mary, Algeria, Monday), given their capital: Ghost Keyboard 0 of 188; Gboard 134 of 191.

