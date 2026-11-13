# Consignes pour les sessions Claude

## GitHub Actions : stockage des artefacts

Le compte ne dispose que de 0,5 Go de stockage Actions par mois, partagé entre tous les dépôts privés. En novembre 2026, ce quota a été dépassé par des artefacts lourds conservés 90 jours.

Pour tout workflow créé ou modifié :

- Chaque étape `actions/upload-artifact` fixe `retention-days` : 3 jours au plus pour un binaire (installateur, APK, archive), 7 jours au plus pour des rapports ou des journaux.
- Ne pas envoyer d'artefact si le fichier est déjà publié ailleurs (release, registre de paquets).
- Ne pas envoyer de binaire lourd à chaque push : le limiter à `main`, aux tags ou à `workflow_dispatch` quand c'est possible.
- Éviter de déclencher deux fois le même travail (`push` et `pull_request` sur la même branche).
