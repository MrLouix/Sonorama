# Sonorama — Spécification

12 nov. 2026 · Louis

## 1. Vision et périmètre

Sonorama (nom de travail précédent : Ambiant Phone) est une appli Android qui joue des sons d'ambiance en boucle, mixés en direct depuis des pages de cartes tactiles. Elle vise deux usages : la lecture d'histoires aux enfants et les parties de jeu de rôle.

Le positionnement est l'épure : une grille de cartes, un bouton play/pause, aucun vocabulaire de jeu de rôle, aucun compte, aucune publicité.

**Principes**

- Utilisable d'une main, sans regarder longtemps l'écran, pendant qu'on lit ou qu'on mène une partie.
- Chaque action a un effet audible ou visible en moins d'une seconde.
- Fonctionne entièrement hors ligne ; l'appli ne demande aucun accès réseau.
- Les transitions sonores se font toujours en fondu, jamais en coupure sèche.

**Hors périmètre de la première version**

- Musique, playlists et lecteur de morceaux.
- Sons ponctuels joués une seule fois (tonnerre, porte, cloche) : option future.
- Catalogue en ligne et téléchargement de packs depuis l'appli, synchronisation cloud, comptes.
- iOS.

## 2. Concepts

L'état de lecture appartient à la carte, pas à la page : une même carte placée sur plusieurs pages a partout le même état et le même volume.

| Concept | Définition |
| --- | --- |
| Son | Fichier audio en boucle sans raccord audible, fourni par un pack |
| Carte simple | Un symbole, un nom, un son, un volume mémorisé |
| Carte composée | Un symbole, un nom, plusieurs sons avec chacun un volume relatif préréglé ; sert aussi à enregistrer un mix complet (scène) |
| Page | Grille ordonnée de cartes ; intégrée (liée à un thème) ou personnalisée |
| Pack | Archive ZIP de sons et de cartes pour un thème, importée depuis le téléphone |
| Mix global | Ensemble des cartes actives, toutes pages confondues |
| Plan | Liste de changements préparés en mode plan, pas encore appliqués |

Une carte composée joue ses propres instances de sons : elle est indépendante des cartes simples qui utilisent les mêmes sons.

## 3. Écrans et navigation

L'appli tient en un écran principal : des pages que l'on fait défiler horizontalement, une grille de cartes, une barre de commande fixe en bas.

**Pages**

- Onglets en haut, balayage latéral pour passer d'une page à l'autre.
- Pages intégrées au lancement : Nature et météo, Ville et machines, Féerique. Peu de pages et bien remplies : le mode vocal n'écoute que les mots-clés de la page affichée. Thèmes envisagés ensuite en packs : Maison, Fantastique. Pas de page Animaux : les cartes d'animaux sont réparties dans les autres thèmes.
- Les pages personnalisées apparaissent après les pages intégrées ; l'ordre des onglets est modifiable.
- Un point sur l'onglet signale qu'au moins une carte de cette page est active.

**Cartes**

- Grille de 3 colonnes en portrait sur téléphone, adaptative sur tablette et en paysage.
- Chaque carte montre un symbole et un nom sur deux lignes au plus.
- États visuels : inactive, active (fond coloré et jauge de volume discrète), en attente d'activation, en attente d'arrêt. L'état ne repose jamais sur la couleur seule.
- Appui court : activer ou désactiver. Appui long : réglage du volume (section 5).
- Mode paramétrage (bouton de la barre haute) : un appui sur une carte, court ou long, ouvre ses mots-clés au lieu de la lancer ; un crayon sur chaque carte et un bandeau le rappellent. Le son en cours continue. On en sort par le bouton, « Terminé » ou le retour ; il n'est pas mémorisé d'une ouverture à l'autre.

**Barre basse**

- Bouton play/pause principal, centré et large.
- Bascule de mode Direct / Plan.
- Compteur de cartes actives ; un appui ouvre le panneau « En cours ».
- En mode plan avec des changements en attente : boutons Appliquer et Annuler.
- Bouton micro du mode vocal, avec le dernier mot-clé reconnu (section 14).

**Panneau « En cours »**

- Liste des cartes actives de toutes les pages, avec curseur de volume et bouton d'arrêt par carte.
- Bouton « Enregistrer ce mix » : crée une carte composée à partir des cartes actives et de leurs volumes (section 7).
- Bouton « Tout arrêter ».

**Visite guidée**

- Au premier lancement, une fois le pack de base installé, une visite de 9 étapes présente l'écran principal : bienvenue, toucher une carte, volume, lecture/pause, panneau « En cours », pages, mode Plan, mode vocal, mots-clés. On la revoit depuis le menu ⋮ (« Visite guidée », en dernier).
- Un voile assombrit l'écran sauf l'élément expliqué, cerné d'un contour ambré ; une bulle (compteur « n / 9 », « Passer », « Suivant », puis « Terminer ») se place à côté. Le reste de l'écran ne réagit pas.
- Deux étapes se font : toucher la carte d'exemple (la première de Nature et météo) et régler son volume. Elles avancent dès que le geste est fait ; « Suivant » reste possible, et un doigt animé montre le geste du volume au bout de 8 s. Les autres étapes montrent sans changer de mode ni ouvrir d'écran ; le mode vocal est expliqué sans demander le micro.
- La visite part d'un écran neutre : page Nature et météo, mode Direct, rien qui joue. Le mix en cours (cartes, lecture ou pause, mode, plan) et le mode vocal sont mis de côté, puis rendus à la fin ou à l'abandon ; la carte d'exemple retrouve son volume. Pendant la visite, le mix enregistré pour le prochain lancement reste celui d'avant.
- Elle compte comme vue dès qu'elle commence, même passée. Le retour la quitte.
- Avec TalkBack, seule la bulle est lisible ; chaque bulle est annoncée et toutes les étapes avancent par « Suivant » (le volume passe par les actions « Augmenter / Diminuer le volume » de la carte).

**Autres écrans**

- Packs : import d'un ZIP, liste des packs installés, suppression.
- Éditeur de page personnalisée et éditeur de carte composée.
- Réglages : durées de fondu, thème clair ou sombre, taille des cartes (normale, compacte, très compacte), langue, mode vocal, licences des sons.

## 4. Modes de lecture

Deux modes décident du moment où un appui sur une carte devient audible : tout de suite en mode direct, à la validation en mode plan.

**Mode direct**

- Un appui sur une carte inactive la lance en fondu d'entrée (0,5 s par défaut).
- Un appui sur une carte active l'arrête en fondu de sortie (0,5 s par défaut). Les durées de fondu sont des réglages globaux, de 0 à 5 s, identiques pour toutes les cartes.

**Mode plan**

- Un appui ne change pas le son : la carte passe « en attente d'activation » ou « en attente d'arrêt ».
- Un réglage de volume sur une carte rejoint lui aussi le plan : la jauge affiche la valeur visée, le son ne change pas avant la validation.
- Seul le volume général du téléphone s'applique tout de suite.
- Un second appui sur la même carte retire le changement du plan.
- Le plan peut porter sur plusieurs pages ; la barre basse affiche le nombre de changements en attente.
- Appliquer : activations, arrêts et changements de volume démarrent au même instant, en fondu croisé (2 s par défaut).
- Annuler : le plan est vidé, le mix en cours reste inchangé.
- Revenir au mode direct avec un plan non vide demande de l'appliquer ou de l'abandonner.

**Play/pause principal**

- Pause : tout le mix s'éteint en fondu, les cartes restent marquées actives.
- Play : le mix reprend en fondu, au même état.
- En pause, activer ou désactiver une carte modifie le mix sans rien jouer ; le résultat s'entend au prochain play.
- Le même bouton est exposé dans la notification, sur l'écran verrouillé et aux casques Bluetooth.

**Changement de page**

- Les cartes actives continuent de jouer quand on change de page.
- Le mix global peut donc combiner des cartes de plusieurs pages.

**Limites**

- 12 sons simultanés au plus (une carte composée compte pour chacun de ses sons). Au-delà, l'activation est refusée avec un message court.
- À la réouverture de l'appli, le dernier mix est restauré en pause.

## 5. Volume par carte

Le volume d'une carte se règle par un appui long suivi d'un glissement vertical, sans lever le doigt.

1. Appui maintenu 400 ms : vibration courte, une jauge verticale recouvre la carte et affiche le pourcentage.
2. Glisser vers le haut augmente, vers le bas diminue ; la course complète fait environ 200 dp.
3. En mode direct, le changement s'entend en temps réel si la carte est active.
4. Relâcher valide : en mode direct le volume est mémorisé ; en mode plan il rejoint le plan et ne s'applique qu'à la validation.

**Règles**

- Plage de 0 à 100 %, valeur par défaut 70 %, courbe perceptive (logarithmique).
- Le volume est mémorisé par carte et conservé entre les sessions.
- Le réglage fonctionne aussi sur une carte inactive, pour préparer un niveau.
- Le défilement de la grille est bloqué pendant le geste.
- Le volume général reste celui du téléphone (touches physiques) ; il s'applique toujours tout de suite, dans les deux modes.
- Alternative sans geste : le curseur du panneau « En cours », et des actions TalkBack « augmenter » et « diminuer ».
- Pour une carte composée, le geste règle le volume d'ensemble ; les volumes relatifs des sons se règlent dans l'éditeur.

## 6. Pages personnalisées

L'utilisateur compose une page en choisissant des cartes parmi toutes celles disponibles, puis l'enregistre sous un nom.

**Création**

1. Bouton « + » au bout des onglets.
2. Saisie d'un nom (30 caractères au plus) et choix d'un symbole.
3. Sélecteur de cartes : toutes les cartes installées, groupées par thème, avec recherche par nom et pré-écoute.
4. Enregistrer : la page apparaît dans les onglets.

**Règles**

- Une page référence des cartes existantes, elle ne les duplique pas : l'état et le volume d'une carte sont les mêmes sur toutes les pages.
- 24 cartes au plus par page ; pas de limite au nombre de pages.
- Édition par appui long sur l'onglet : renommer, ajouter ou retirer des cartes, réordonner par glisser-déposer, dupliquer, supprimer.
- Supprimer une page ne supprime ni les cartes ni les sons.
- Les pages intégrées ne sont pas modifiables ; « Dupliquer » en crée une copie personnalisable.
- Si un pack est désinstallé, ses cartes restent affichées grisées avec une action « Réimporter le pack ».

## 7. Cartes composées et scènes

Une carte composée regroupe plusieurs sons sous un nom et un symbole, avec un volume préréglé pour chacun ; elle se comporte ensuite comme une carte simple.

**Création**

1. Depuis le sélecteur de cartes ou l'éditeur de page : « Nouvelle carte composée ».
2. Nom (24 caractères au plus) et symbole choisi dans la liste fournie.
3. Ajout de 2 à 12 sons parmi tous les sons installés.
4. Réglage du volume relatif de chaque son, avec écoute en direct du mélange.
5. Enregistrer : la carte rejoint la bibliothèque, dans un groupe « Mes cartes ».

**Règles**

- Activer la carte lance tous ses sons ensemble ; la désactiver les arrête ensemble.
- Volume final d'un son = volume relatif × volume d'ensemble de la carte.
- La carte s'utilise en mode direct comme en mode plan, et se place sur n'importe quelle page personnalisée.
- Elle est modifiable et supprimable ; la supprimer la retire de toutes les pages.
- Liste de symboles : environ 150 pictogrammes Material Symbols (licence Apache 2.0), classés par thème.

**Scène : enregistrer le mix en cours**

- Depuis le panneau « En cours », « Enregistrer ce mix » crée une carte composée contenant tous les sons actifs, à leurs volumes du moment.
- Il reste à lui donner un nom et un symbole ; elle se place ensuite sur une page personnalisée.
- Les cartes composées déjà actives sont mises à plat : la scène ne contient que des sons, 12 au plus.
- Rappeler une scène l'ajoute au mix en cours, comme toute carte ; elle ne remplace pas ce qui joue déjà : en mode direct elle démarre tout de suite, en mode plan à la validation.

## 8. Packs de sons

L'appli embarque un petit pack de base pour fonctionner dès l'installation ; les autres sons arrivent par packs ZIP, téléchargés hors de l'appli puis importés depuis le dossier Téléchargements.

**Pack de base (dans l'APK)**

- 36 sons sur trois pages intégrées (Nature et météo, Ville et machines, Féerique) ; Féerique n'en compte que deux au départ.
- Objectif : APK sous 30 Mo.

**Import d'un pack**

1. Les packs ZIP sont publiés dans un dépôt Git public ou sur une page web ; l'utilisateur les récupère avec son navigateur.
2. Dans l'appli, menu Packs puis « Importer un pack ».
3. Le sélecteur de fichiers du système s'ouvre sur Téléchargements, filtré sur les fichiers ZIP ; plusieurs fichiers peuvent être choisis d'un coup.
4. L'appli vérifie l'archive : manifeste présent et lisible, version de format connue, fichiers audio déclarés tous présents.
5. Tout le contenu est copié dans le dossier privé de l'appli ; les cartes et la page du pack sont créées. Le ZIP d'origine peut ensuite être supprimé.
6. Un écran de bilan indique le nombre de sons ajoutés, ou la raison précise d'un refus.

**Règles d'import**

- Réimporter un pack de version supérieure le met à jour ; les volumes, pages et cartes composées de l'utilisateur sont conservés.
- Réimporter la même version propose de remplacer ou d'ignorer.
- Garde-fous : chemins de l'archive contrôlés, taille décompressée limitée à 500 Mo, seuls les fichiers déclarés au manifeste sont copiés.
- L'import tourne en tâche de fond avec une barre de progression et peut être annulé.
- Supprimer un pack libère ses fichiers ; ses cartes restent grisées sur les pages personnalisées.
- Le format étant documenté, chacun peut fabriquer son propre pack : c'est la voie pour ajouter ses sons personnels.

**Format d'un pack**

- Archive ZIP : `manifest.json`, fichiers audio, `LICENSES.md`.
- Le manifeste décrit chaque son (fichier, nom en français et en anglais, symbole, volume par défaut, auteur, licence, source) et la page intégrée à créer ou à compléter.
- Audio : Ogg Vorbis, 44,1 kHz, stéréo, environ 128 kbit/s, boucles de 30 à 120 s préparées pour un raccord inaudible, niveau normalisé entre les sons.
- Un pack ne contient que des données, jamais de code.

**Licences**

- Sons sous CC0 de préférence, CC BY accepté avec attribution. Aucune licence « non commercial » ni « pas de modification ».
- Un écran « Licences des sons » affiche auteur, licence et source de chaque son installé.

## 9. Architecture technique

Recommandation : appli native en Kotlin avec Jetpack Compose et Media3. Le projet est Android uniquement, le cœur du produit est l'audio en arrière-plan, et cette pile ne dépend d'aucun composant propriétaire, ce qui la rend compatible F-Droid.

| Brique | Choix |
| --- | --- |
| Langage et interface | Kotlin, Jetpack Compose, Material 3 |
| Architecture | MVVM, flux d'état unidirectionnel, coroutines et Flow |
| Moteur audio | Media3 ExoPlayer, un lecteur par son actif, boucle en `REPEAT_MODE_ONE` |
| Arrière-plan | `MediaSessionService` en service de premier plan, notification média |
| Données | Room (cartes, pages, packs), DataStore (réglages, dernier mix) |
| Import de packs | Sélecteur de fichiers du système (Storage Access Framework), décompression en tâche de fond |
| Android minimum | 8.0 (API 26), cible : dernière API stable |
| Licence du code | GPL-3.0 |

**Moteur de mixage**

- Un composant `MixEngine` unique possède l'état du mix et reçoit des commandes : activer, désactiver, régler le volume, appliquer un plan, pause, reprise.
- L'interface ne parle jamais directement aux lecteurs ; elle observe l'état du mix.
- Les fondus sont des rampes de volume de 20 ms de pas, pilotées par le moteur.
- Appliquer un plan prépare d'abord tous les lecteurs, puis déclenche les fondus au même instant.
- Focus audio : pause sur appel entrant, baisse temporaire sur notification, pause au débranchement du casque.

**Risque à lever par prototype**

- Le raccord de boucle d'ExoPlayer et la charge de 12 lecteurs simultanés sont à mesurer sur un téléphone d'entrée de gamme avant de figer ce choix.
- Solution de repli : un mixeur maison sur `AudioTrack` ou Oboe, qui décode et additionne les flux lui-même.

## 10. Distribution

Recommandation : publier d'abord sur F-Droid, en gardant un build unique publiable tel quel sur le Play Store plus tard. Le projet (gratuit, sans pub, sans compte, sons libres) correspond déjà aux critères F-Droid, et le Play Store impose un délai d'entrée à un nouveau compte personnel.

| Critère | F-Droid | Play Store gratuit |
| --- | --- | --- |
| Coût | Aucun | 25 USD, une fois |
| Condition d'entrée | Code source public sous licence libre, compilé par F-Droid | Compte personnel créé après le 13 nov. 2023 : test fermé avec 12 testeurs inscrits pendant 14 jours continus |
| Contraintes | Aucune bibliothèque propriétaire de suivi ou de publicité ; ressources sous licence valide | Fiches de conformité, politique de confidentialité, revue Google |
| Audience | Restreinte, public technique | Large, public familial |

**Conséquences pour la conception**

- Aucune dépendance à Google Play Services ni à Firebase.
- Code source public dès le départ, avec un historique de versions étiquetées.
- Packs publiés en téléchargement direct sur une page publique ; l'appli elle-même n'accède jamais au réseau.
- Pour toucher les familles, le Play Store reste la cible à moyen terme ; le test fermé peut démarrer en parallèle de la sortie F-Droid.

Sources : [politique d'inclusion F-Droid](https://f-droid.org/fr/docs/Inclusion_Policy), [exigences de test Google Play](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en), [guide de publication Google Play](https://www.fabricadeaplicativos.com.br/guides/publish-app-to-google-play).

## 11. Exigences non fonctionnelles

**Accessibilité**

- Cibles tactiles d'au moins 64 dp pour les cartes, 48 dp pour les autres commandes.
- Contraste conforme WCAG AA ; états des cartes distingués par forme et icône, pas seulement par couleur.
- Compatibilité TalkBack : chaque carte annonce nom, état et volume.
- Respect de la taille de police système jusqu'à 200 %.
- Thèmes clair et sombre ; le sombre est adapté à la lecture du soir.

**Performance**

- Délai entre l'appui et le début du fondu : moins de 150 ms.
- Démarrage à froid : moins de 2 s sur un téléphone d'entrée de gamme.
- Aucun raccord audible sur les boucles ; aucun craquement lors des fondus.
- Lecture continue écran éteint pendant au moins 2 h sans coupure.

**Hors ligne et vie privée**

- Tout fonctionne hors ligne ; les packs s'importent depuis un fichier local.
- Aucun compte, aucune publicité, aucune télémétrie.
- Permissions : service de premier plan (lecture média et micro), notifications et micro, demandé seulement au premier usage du mode vocal. Ni Internet ni accès au stockage.
- Le micro n'est ouvert que pendant l'écoute du mode vocal ; aucun son n'est enregistré.

**Langues**

- Français et anglais au lancement ; toutes les chaînes, noms de sons compris, sont traduisibles.

## 12. Lots de livraison

Un prototype puis trois lots, chacun utilisable seul ; le mode plan est dans le MVP parce qu'il distingue l'appli de l'existant.

| Lot | Contenu | Critère de sortie |
| --- | --- | --- |
| 0 — Prototype audio | `MixEngine`, 12 boucles simultanées, fondus, lecture en arrière-plan | Aucun raccord audible, charge acceptable sur téléphone d'entrée de gamme |
| 1 — MVP | Pages intégrées, pack de base, mode direct, mode plan, play/pause, volume par appui long, panneau « En cours » | Une histoire lue de bout en bout sans toucher aux réglages |
| 2 — V1 | Pages personnalisées, import de packs ZIP, écran des licences, français et anglais, publication F-Droid | Une page composée et retrouvée après redémarrage ; un pack importé depuis Téléchargements puis joué |
| 3 — V2 | Cartes composées, enregistrement d'un mix en scène, export et import de pages | Une carte composée créée, placée sur une page et jouée en mode plan |

## 13. Décisions et points restants

Toutes les questions ouvertes ont été tranchées le 12 nov. 2026 ; trois points restent à traiter avant ou pendant le développement.

**Décisions**

- Nom : Sonorama.
- Sons ponctuels : pas pour le moment, option future.
- Mode plan : tout s'applique à la validation, sauf le volume général du téléphone.
- Scènes : un mix s'enregistre comme carte composée ; rappelée, elle s'ajoute au mix en cours.
- Packs : ZIP publiés dans un dépôt Git public ou sur une page web, importés depuis Téléchargements.
- Thèmes au lancement : Nature et météo, Ville et machines, Féerique ; les animaux sont répartis dans ces thèmes.
- Pas de minuterie d'arrêt, pas de verrouillage tactile.
- Fondus réglables de 0 à 5 s, réglage global.
- Grille adaptative en paysage et sur tablette, sans mise en page dédiée.
- Licence du code : GPL-3.0.

**Points restants**

- [ ] Vérifier les marques déposées « Sonorama » (INPI, EUIPO) avant publication.
- [ ] Lever le risque audio par le prototype du lot 0 : raccord de boucle et 12 sons simultanés.
- [ ] Constituer le pack de base : boucles sous CC0 ou CC BY, normalisées. 22 enregistrements acceptés les 25 et 26 nov. 2026, 3 sons générés par Stable Audio Open (chouette, vache, chat) le 27 nov. ; 11 sons restent synthétisés.

## 14. Mode vocal

Ajouté le 24 nov. 2026. L'appli écoute la personne qui lit l'histoire et déclenche, pendant quelques secondes, les cartes dont elle reconnaît un mot-clé. Le mode vocal s'utilise en même temps que le mix standard.

**Principe**

- Un bouton micro dans la barre basse allume et éteint l'écoute ; il affiche le dernier mot-clé reconnu.
- Seules les cartes de la page affichée peuvent être déclenchées. Une page par histoire permet de choisir les sons possibles.
- Un mot-clé reconnu lance toutes les cartes de la page qui le portent, tout de suite, quel que soit le mode (direct ou plan) et sans toucher au plan.
- Le son joue pendant une durée réglable (3 à 30 s, 5 s par défaut), avec un fondu d'entrée de 0,5 s et de sortie de 1 s. Un mot-clé redit pendant ce temps relance le compte à rebours.
- Un anneau sur la carte montre le temps restant.

**Cohabitation avec le mix**

- Une carte déjà active dans le mix n'est pas relancée par la voix.
- Les sons déclenchés à la voix comptent dans la limite de 12 sons ; à la limite, le plus ancien son vocal s'arrête pour faire place. Les sons du mix ne sont jamais arrêtés par la voix.
- Mix vide : un mot-clé lance la lecture.
- Pause : aucun son ne joue et le micro est coupé ; les sons vocaux en cours s'arrêtent. À la reprise, l'écoute reprend si elle était allumée.
- Un appui n'importe où sur une carte déclenchée à la voix la garde en lecture, sans arrêt automatique, comme si elle avait été lancée à la main : elle rejoint le mix enregistré et ne cède plus sa place aux sons vocaux. Un second appui l'arrête. Effet immédiat, y compris en mode plan. L'anneau porte une épingle pour le rappeler ; TalkBack annonce l'action « Garder en lecture ».
- Le bouton d'arrêt du panneau « En cours » arrête directement une carte déclenchée à la voix.

**Écoute**

- Reconnaissance hors ligne par Vosk, de toute la phrase, dont on extrait les mots-clés de la page ; déclenchement sur les résultats partiels, dès que le mot est entendu avec une confiance d'au moins 0,6, ou qu'il tient 0,4 s. L'APK de débogage permet de comparer avec une reconnaissance limitée aux mots-clés (voir « Décisions »).
- L'écoute démarre depuis l'appli ouverte et continue écran éteint, avec une notification « micro actif » qui permet de la couper.
- Elle s'arrête d'elle-même après 5 minutes sans mot-clé reconnu.
- Micro ouvert en mode « reconnaissance vocale », avec l'annulation d'écho du téléphone quand elle existe.

**Mots-clés**

- Le pack déclare des mots-clés par son et par langue ; ils se modifient en touchant une carte en mode paramétrage, ou pour toute la page depuis son onglet (appui long, « Mots-clés ») ; l'appui long sur une carte reste le réglage du volume. Les mots-clés modifiés priment et survivent aux mises à jour du pack ; un bouton rétablit ceux du pack.
- Une carte composée n'a que les mots-clés qu'on lui donne.
- Casse et accents ignorés ; un mot-clé peut compter plusieurs mots ; les variantes (singulier, pluriel) sont déclarées une à une.
- Un mot que le modèle de langue ne connaît pas est signalé et grisé : il ne sera jamais reconnu.

**Langues et modèles**

- La langue de l'histoire est un réglage à part, qui suit par défaut la langue de l'appli.
- Le modèle français est intégré à l'APK. Le modèle anglais s'importe depuis un fichier ZIP ; seuls les modèles officiels Vosk, reconnus par leur empreinte SHA-256, sont acceptés.
- Un APK par type de processeur, pour limiter la taille.

**Transcription (versions de test uniquement)**

- Dans l'APK de débogage, un réglage enregistre le texte entendu, jamais l'audio, pour repérer les sons à ajouter.
- Chaque phrase est horodatée, avec les cartes déclenchées, la page affichée, les mots-clés reconnus et la phrase en cours au moment du déclenchement, la confiance de chaque mot et le niveau du micro ; export CSV par le sélecteur de fichiers, effacement en un bouton.
- Le code de la transcription n'existe pas dans l'APK de publication.

**Décisions**

- Reconnaissance de toute la phrase par défaut. Un essai sur voix de synthèse (24 nov. 2026) a montré que le mode limité aux mots-clés invente des mots-clés non prononcés et en rate d'autres ; le mode complet s'est moins trompé. À confirmer sur une vraie voix : les deux modes sont comparables dans l'APK de débogage.
- Confiance ou stabilité, jamais la confiance seule. Mesuré le 25 nov. 2026 sur 75 phrases de synthèse : le seuil seul perd 3 à 5 mots-clés, la combinaison n'en perd aucun. Les phrases pièges n'ont produit aucune fausse alerte, avec ou sans filtre : son utilité reste à mesurer sur une vraie lecture.
- Voix d'enfant : second reconnaisseur sur le signal abaissé de 16 %, réglage de l'APK de débogage, éteint par défaut. Sur une voix d'enfant simulée il fait passer de 73 à 75 mots-clés sur 75 (de 69 à 73 dans le bruit) ; à confirmer sur une vraie voix, pour environ deux fois plus de calcul.
- Une pause alors que seuls des sons vocaux jouent reste une pause : le micro reste coupé jusqu'au play.
