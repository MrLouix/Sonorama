# Politique de sécurité

## Versions suivies

Seule la dernière version publiée de Sonorama reçoit des correctifs de sécurité.

## Signaler une vulnérabilité

Merci de **ne pas ouvrir de ticket public** pour une faille de sécurité.

Utilisez le signalement privé de GitHub : onglet **Security** du dépôt, puis **Report a vulnerability**.
Décrivez le problème, la version concernée et, si possible, les étapes pour le reproduire.

Vous recevrez un accusé de réception sous sept jours. Une fois le correctif publié, la faille est décrite
dans le [journal des modifications](CHANGELOG.md), avec votre nom si vous le souhaitez.

## Périmètre

Sonorama n'a aucun accès réseau, aucun compte et ne collecte aucune donnée. La surface d'attaque se limite
surtout à l'**import de fichiers** : packs ZIP et fichiers de page JSON. Sont particulièrement utiles les
signalements portant sur :

- une archive qui écrit hors du dossier privé de l'appli (traversée de chemin) ;
- une archive qui épuise le stockage ou la mémoire (bombe de décompression) ;
- un manifeste ou un fichier de page qui fait planter l'appli ou corrompt la bibliothèque.
