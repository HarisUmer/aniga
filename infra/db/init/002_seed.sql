-- Hand-written seed so the recommender and web console work before any external API sync.
-- Synopses are short original summaries (not copied from AniList/MAL).

INSERT INTO media (media_type, title, synopsis, genres, tags, episodes, chapters, start_year, popularity, external_source, external_id) VALUES
('ANIME', 'Naruto', 'An outcast ninja kid chases recognition, trains with rivals and friends, and fights through village exams and tournaments.', '{Action,Adventure}', '{shonen,ninja,friendship,rivalry,tournament,training}', 220, NULL, 2002, 95, 'seed', 'a-naruto'),
('ANIME', 'Attack on Titan', 'Humanity survives behind walls until titans break in; a vengeful soldier uncovers a dark political conspiracy and a brutal war for survival.', '{Action,Drama,Mystery}', '{shonen,military,survival,tragedy,political,dark,revenge}', 87, NULL, 2013, 99, 'seed', 'a-aot'),
('ANIME', 'Demon Slayer', 'After demons slaughter his family, a kind swordsman hunts them to cure his sister, facing dark and tragic demon pasts.', '{Action,Supernatural}', '{shonen,demons,swordsman,family,revenge,dark}', 63, NULL, 2019, 97, 'seed', 'a-kny'),
('ANIME', 'My Hero Academia', 'A powerless boy inherits a superpower and enrolls in a hero school full of classmates, festivals and tournaments.', '{Action,Comedy}', '{shonen,superpowers,school,tournament,training,friendship}', 159, NULL, 2016, 93, 'seed', 'a-mha'),
('ANIME', 'Death Note', 'A student finds a notebook that kills and plays deadly psychological mind games against a genius detective.', '{Mystery,Thriller,Supernatural}', '{psychological,detective,mind-games,dark,crime}', 37, NULL, 2006, 96, 'seed', 'a-dn'),
('ANIME', 'Fullmetal Alchemist: Brotherhood', 'Two brothers pay a terrible price for alchemy and uncover a military conspiracy while searching for a way to restore their bodies.', '{Action,Adventure,Drama,Fantasy}', '{shonen,alchemy,brothers,military,conspiracy,political}', 64, NULL, 2009, 98, 'seed', 'a-fmab'),
('ANIME', 'Your Lie in April', 'A grieving piano prodigy rediscovers music through a free-spirited violinist in a bittersweet school romance.', '{Drama,Romance,Music}', '{school,piano,grief,tragedy,coming-of-age}', 22, NULL, 2014, 88, 'seed', 'a-ylia'),
('ANIME', 'Haikyu!!', 'A short but determined player joins a school volleyball team and grows through rivalry, teamwork and tournaments.', '{Sports,Comedy}', '{shonen,volleyball,teamwork,school,rivalry,tournament}', 85, NULL, 2014, 90, 'seed', 'a-haikyu'),
('ANIME', 'Steins;Gate', 'A self-styled mad scientist discovers time travel through text messages and spirals into a psychological conspiracy.', '{Sci-Fi,Thriller,Drama}', '{time-travel,conspiracy,psychological,tragedy}', 24, NULL, 2011, 91, 'seed', 'a-sg'),
('ANIME', 'Spy x Family', 'A spy, an assassin and a telepathic child fake a family for a mission and become a real found family.', '{Action,Comedy,Slice of Life}', '{found-family,spy,school,wholesome}', 37, NULL, 2022, 92, 'seed', 'a-sxf'),
('ANIME', 'Mob Psycho 100', 'An overpowered psychic middle schooler tries to live normally while learning that power is not what makes a person.', '{Action,Comedy,Supernatural}', '{shonen,psychic,coming-of-age,overpowered,school}', 37, NULL, 2016, 89, 'seed', 'a-mob'),
('ANIME', 'Violet Evergarden', 'A former child soldier becomes a letter writer after the war and slowly learns the meaning of love and grief.', '{Drama,Fantasy,Slice of Life}', '{postwar,letters,grief,military,emotional}', 13, NULL, 2018, 87, 'seed', 'a-violet'),
('ANIME', 'Jujutsu Kaisen', 'A student swallows a cursed finger and joins a school of sorcerers fighting dark curses with brutal stakes.', '{Action,Supernatural}', '{shonen,curses,school,dark,tragedy}', 47, NULL, 2020, 96, 'seed', 'a-jjk'),
('ANIME', 'Frieren: Beyond Journey''s End', 'An elf mage outlives her hero party and travels again to understand time, memory and the people she lost.', '{Adventure,Drama,Fantasy}', '{elf,journey,grief,magic,slow-burn}', 28, NULL, 2023, 94, 'seed', 'a-frieren'),
('MANGA', 'One Piece', 'A rubber-bodied boy sails with a crew of friends to find a legendary treasure across a vast world of islands.', '{Action,Adventure,Comedy}', '{shonen,pirates,friendship,world-building,adventure}', NULL, 1150, 1997, 99, 'seed', 'm-op'),
('MANGA', 'Berserk', 'A lone mercenary swordsman seeks revenge against a betraying friend in a dark, demon-filled medieval world.', '{Action,Drama,Fantasy,Horror}', '{seinen,revenge,dark,mercenary,tragedy,swordsman}', NULL, 380, 1989, 95, 'seed', 'm-berserk'),
('MANGA', 'Vagabond', 'A violent young swordsman walks the path of the sword and slowly learns philosophy, restraint and humanity.', '{Action,Drama}', '{seinen,samurai,swordsman,philosophy,historical}', NULL, 327, 1998, 88, 'seed', 'm-vagabond'),
('MANGA', 'Chainsaw Man', 'A broke devil hunter fuses with his chainsaw devil and is pulled into chaotic, dark and violent government work.', '{Action,Horror,Comedy}', '{shonen,devils,dark,chaotic,gore}', NULL, 200, 2018, 94, 'seed', 'm-csm'),
('MANGA', 'Kaguya-sama: Love is War', 'Two student council geniuses wage comedic mind games to make the other confess love first.', '{Comedy,Romance}', '{school,mind-games,student-council,romcom}', NULL, 281, 2015, 90, 'seed', 'm-kaguya'),
('MANGA', 'Monster', 'A surgeon saves a boy who grows into a serial killer and hunts him across Europe in a psychological thriller.', '{Mystery,Thriller,Drama}', '{seinen,psychological,doctor,crime,dark}', NULL, 162, 1994, 90, 'seed', 'm-monster'),
('MANGA', 'Dandadan', 'A girl who believes in ghosts and a boy who believes in aliens discover both are real in a chaotic supernatural romance.', '{Action,Comedy,Supernatural}', '{shonen,aliens,ghosts,romance,chaotic}', NULL, 200, 2021, 91, 'seed', 'm-dandadan'),
('MANHWA', 'Solo Leveling', 'The weakest hunter gains a hidden leveling system and grows into an overpowered force inside monster dungeons.', '{Action,Fantasy}', '{dungeons,leveling,overpowered,hunters,system}', NULL, 179, 2018, 98, 'seed', 'w-solo'),
('MANHWA', 'Tower of God', 'A boy enters a mysterious tower to follow his friend, climbing floors of deadly trials, alliances and betrayal.', '{Action,Adventure,Fantasy,Mystery}', '{tower,trials,betrayal,world-building}', NULL, 600, 2010, 92, 'seed', 'w-tog'),
('MANHWA', 'The Beginning After the End', 'A king reincarnates in a world of magic and relives childhood, academy life and war with adult memories.', '{Action,Fantasy,Adventure}', '{reincarnation,magic,academy,overpowered}', NULL, 200, 2018, 93, 'seed', 'w-tbate'),
('MANHWA', 'Omniscient Reader''s Viewpoint', 'The only reader of a web novel watches it become reality and uses his knowledge of the scenarios to survive the apocalypse.', '{Action,Fantasy}', '{apocalypse,novel,scenarios,survival,system}', NULL, 250, 2020, 95, 'seed', 'w-orv'),
('MANHWA', 'True Beauty', 'A girl hides behind makeup at a new school and navigates romance, friendship and self-image.', '{Comedy,Romance,Drama}', '{school,makeup,self-image,romcom}', NULL, 230, 2018, 85, 'seed', 'w-truebeauty'),
('MANHWA', 'Lookism', 'A bullied student wakes up in a second, perfect body and switches between two lives full of street fights.', '{Action,Drama}', '{school,body-swap,bullying,street-fights}', NULL, 500, 2014, 88, 'seed', 'w-lookism'),
('MANHWA', 'Eleceed', 'A kind, lightning-fast boy is trained by a grumpy superhuman stuck in the body of a fat cat.', '{Action,Comedy,Fantasy}', '{superpowers,mentor,training,cat}', NULL, 320, 2018, 89, 'seed', 'w-eleceed'),
('MANHUA', 'Tales of Demons and Gods', 'A demon spiritist returns to his youth with memories of the future and cultivates fast to prevent his city''s fall and take revenge.', '{Action,Fantasy}', '{cultivation,reincarnation,revenge,overpowered}', NULL, 480, 2015, 87, 'seed', 'h-tdg'),
('MANHUA', 'Martial Peak', 'A humble sweeper of a martial sect finds a black book and climbs the cultivation peak through endless battles.', '{Action,Fantasy}', '{cultivation,martial-arts,sect,overpowered}', NULL, 3800, 2018, 84, 'seed', 'h-mp'),
('MANHUA', 'The King''s Avatar', 'A retired esports legend starts over in an internet cafe and climbs back to the top of a competitive online game.', '{Action,Sports}', '{esports,gaming,comeback,teamwork}', NULL, 300, 2017, 83, 'seed', 'h-tka'),
('MANHUA', 'Grandmaster of Demonic Cultivation', 'A feared cultivator is summoned back to life and solves a ghostly mystery with a righteous sect disciple.', '{Fantasy,Mystery,Drama}', '{cultivation,ghosts,sect,brotherhood,mystery}', NULL, 250, 2019, 90, 'seed', 'h-mdzs'),
('MANHUA', 'Soul Land', 'A reincarnated weapon master awakens spirit powers and trains at an academy for a world of soul masters.', '{Action,Fantasy,Adventure}', '{cultivation,academy,spirits,reincarnation}', NULL, 400, 2011, 86, 'seed', 'h-soulland');

INSERT INTO app_user (username, display_name) VALUES
('kenji', 'Kenji (dark shonen fan)'),
('mina', 'Mina (drama and romance)'),
('lee', 'Lee (manhwa power fantasy)');

-- kenji demonstrates the "two shonen, different scores" case: Naruto 5 vs Attack on Titan 10.
INSERT INTO rating (user_id, media_id, score)
SELECT u.id, m.id, r.score
FROM (VALUES
    ('kenji', 'Naruto', 5),
    ('kenji', 'Attack on Titan', 10),
    ('kenji', 'Demon Slayer', 9),
    ('kenji', 'My Hero Academia', 4),
    ('kenji', 'Jujutsu Kaisen', 9),
    ('kenji', 'Haikyu!!', 5),
    ('kenji', 'Berserk', 10),
    ('mina', 'Your Lie in April', 10),
    ('mina', 'Violet Evergarden', 9),
    ('mina', 'True Beauty', 8),
    ('mina', 'Kaguya-sama: Love is War', 9),
    ('mina', 'Chainsaw Man', 3),
    ('lee', 'Solo Leveling', 10),
    ('lee', 'Omniscient Reader''s Viewpoint', 9),
    ('lee', 'Tower of God', 8),
    ('lee', 'Tales of Demons and Gods', 7),
    ('lee', 'Death Note', 6)
) AS r (username, title, score)
JOIN app_user u ON u.username = r.username
JOIN media m ON m.title = r.title;

INSERT INTO source_adapter (code, display_name, kind, base_url, enabled, notes) VALUES
('mangadex', 'MangaDex', 'OFFICIAL_API', 'https://api.mangadex.org', TRUE,
 'Official public API. Must credit MangaDex and scanlation groups; no ads or paid features.');
