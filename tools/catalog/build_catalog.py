#!/usr/bin/env python3
"""Reproduce built-in catalog assets from GeoNames country dumps and verified Skadi files.

Downloads are developer preparation inputs, never user-facing URL entry. See
../../docs/region-catalog.md. This script does not download or publish anything.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import zipfile

# Each region: observer bounds [south, west, north, east], a viewpoint inside them, and a
# viewing radius. Terrain tiles are computed automatically so coverage always satisfies the
# installer's bounds-plus-radius rule. Peak snapshots merge every listed GeoNames country.
R = dict
REGIONS = [
    # Broad observer areas, with terrain and names extending across administrative borders.
    R(id='uttarakhand-himalaya', name='Uttarakhand Himalaya', country='India',
      description='Garhwal and Kumaon, with surrounding peaks in Tibet, Nepal and Himachal', codes=['IN','CN','NP','PK'],
      observerBounds=[28.70,77.50,31.50,81.10], viewpoint=[30.53,79.57], radiusM=250000, hills=True),
    R(id='jharkhand', name='Jharkhand · Hills and Plateau', country='India',
      description='Ranchi, Parasnath, Netarhat, Dalma and Rajmahal, with surrounding hills', codes=['IN','NP','BD','BT','CN'],
      observerBounds=[21.90,83.20,25.40,88.00], viewpoint=[23.3441,85.3096], radiusM=200000, hills=True),
    # --- Nepal ---
    R(id='poon-hill', name='Poon Hill · Annapurna', country='Nepal', description='Poon Hill and Ghorepani viewpoints · Annapurna Himalaya', codes=['NP'],
      observerBounds=[28.38,83.63,28.46,83.78], viewpoint=[28.4,83.69], radiusM=40000),
    R(id='gokyo', name='Gokyo · Everest', country='Nepal', description='Gokyo valley viewpoints · Khumbu Himalaya', codes=['NP'],
      observerBounds=[27.92,86.64,28.02,86.70], viewpoint=[27.95,86.68], radiusM=25000),
    R(id='everest-base-camp', name='Kala Patthar · Everest', country='Nepal', description='Gorak Shep, Kala Patthar and Everest Base Camp · Khumbu Himalaya', codes=['NP'],
      observerBounds=[27.96,86.80,27.995,86.84], viewpoint=[27.9958,86.8285], radiusM=15000),
    R(id='annapurna-base-camp', name='Annapurna Base Camp', country='Nepal', description='Annapurna Sanctuary trek · Himalaya', codes=['NP'],
      observerBounds=[28.50,83.85,28.55,83.90], viewpoint=[28.5306,83.8788], radiusM=25000),
    R(id='langtang', name='Langtang · Kyanjin Gompa', country='Nepal', description='Kyanjin Gompa and Kyanjin Ri · Langtang Himalaya', codes=['NP'],
      observerBounds=[28.19,85.52,28.23,85.60], viewpoint=[28.2115,85.5675], radiusM=20000),
    R(id='nagarkot', name='Nagarkot', country='Nepal', description='Himalaya sunrise viewpoint near Kathmandu', codes=['NP'],
      observerBounds=[27.70,85.50,27.73,85.54], viewpoint=[27.715,85.52], radiusM=40000),
    # --- India ---
    R(id='auli', name='Auli · Garhwal', country='India', description='Auli and Gorson viewpoints · Garhwal Himalaya', codes=['IN'],
      observerBounds=[30.50,79.55,30.55,79.60], viewpoint=[30.53,79.57], radiusM=30000),
    R(id='chopta-tungnath', name='Chopta · Tungnath', country='India', description='Tungnath and Chandrashila summit · Garhwal Himalaya', codes=['IN'],
      observerBounds=[30.47,79.21,30.51,79.25], viewpoint=[30.4899,79.2184], radiusM=20000),
    R(id='valley-of-flowers', name='Valley of Flowers · Hemkund', country='India', description='Ghangaria, Hemkund Sahib and the valley · Garhwal Himalaya', codes=['IN'],
      observerBounds=[30.70,79.58,30.74,79.64], viewpoint=[30.72,79.60], radiusM=20000),
    R(id='kedarnath', name='Kedarnath', country='India', description='Kedarnath temple and the Chorabari trail · Garhwal Himalaya', codes=['IN'],
      observerBounds=[30.72,79.05,30.75,79.09], viewpoint=[30.7346,79.0669], radiusM=20000),
    R(id='triund', name='Triund · Dharamshala', country='India', description='Triund ridge above McLeod Ganj · Dhauladhar Himalaya', codes=['IN'],
      observerBounds=[32.22,76.32,32.27,76.40], viewpoint=[32.2437,76.3528], radiusM=20000),
    R(id='nainital', name='Nainital · Naina Peak', country='India', description='Naina Peak and Snow View · Kumaon Himalaya', codes=['IN'],
      observerBounds=[29.37,79.42,29.42,79.47], viewpoint=[29.395,79.44], radiusM=40000),
    # --- Jharkhand (small areas; the state-wide pack is not offered as a download) ---
    R(id='parasnath', name='Parasnath · Shikharji', country='India', description='Parasnath Hill, the state\'s highest summit · Jharkhand', codes=['IN'],
      observerBounds=[23.94,86.10,23.99,86.17], viewpoint=[23.9628,86.1288], radiusM=40000),
    R(id='netarhat', name='Netarhat · Magnolia Point', country='India', description='Netarhat sunrise and Magnolia Point · Jharkhand', codes=['IN'],
      observerBounds=[23.45,84.23,23.51,84.30], viewpoint=[23.4826,84.2672], radiusM=40000),
    R(id='ranchi-hills', name='Ranchi · Tagore Hill', country='India', description='Tagore Hill, Pahari Mandir and the Ranchi plateau · Jharkhand', codes=['IN'],
      observerBounds=[23.33,85.28,23.41,85.37], viewpoint=[23.3713,85.3268], radiusM=40000),
    R(id='dalma', name='Dalma Hills', country='India', description='Dalma wildlife sanctuary ridge near Jamshedpur · Jharkhand', codes=['IN'],
      observerBounds=[22.85,86.15,22.92,86.27], viewpoint=[22.8857,86.2207], radiusM=40000),
    R(id='rajmahal', name='Rajmahal Hills', country='India', description='Rajmahal hills and Sahibganj on the Ganga · Jharkhand', codes=['IN'],
      observerBounds=[24.95,87.60,25.25,87.85], viewpoint=[25.05,87.75], radiusM=40000),
    # --- More of India ---
    R(id='tawang', name='Tawang', country='India', description='Tawang monastery and the eastern Himalaya · Arunachal Pradesh', codes=['IN', 'BT', 'CN', 'MM', 'BD'],
      observerBounds=[27.556,91.839,27.616,91.899], viewpoint=[27.586,91.869], radiusM=30000),
    R(id='sela-pass', name='Sela Pass', country='India', description='Sela Lake and pass on the Tawang road · Arunachal Pradesh', codes=['IN', 'BT', 'CN', 'MM', 'BD'],
      observerBounds=[27.475,92.074,27.535,92.134], viewpoint=[27.505,92.104], radiusM=30000),
    R(id='bomdila', name='Bomdila', country='India', description='Bomdila and Dirang valley views · Arunachal Pradesh', codes=['IN', 'BT', 'CN', 'MM', 'BD'],
      observerBounds=[27.234,92.394,27.294,92.454], viewpoint=[27.264,92.424], radiusM=30000, hills=True),
    R(id='ziro', name='Ziro Valley', country='India', description='Ziro plateau and surrounding ridges · Arunachal Pradesh', codes=['IN', 'BT', 'CN', 'MM', 'BD'],
      observerBounds=[27.514,93.798,27.574,93.858], viewpoint=[27.544,93.828], radiusM=30000),
    R(id='mechuka', name='Mechuka', country='India', description='Mechuka valley near the Tibet border · Arunachal Pradesh', codes=['IN', 'BT', 'CN', 'MM', 'BD'],
      observerBounds=[28.570,94.100,28.630,94.160], viewpoint=[28.6,94.13], radiusM=30000, hills=True),
    R(id='shillong-peak', name='Shillong Peak', country='India', description='Shillong Peak viewpoint · Meghalaya', codes=['IN', 'BT', 'CN', 'MM', 'BD'],
      observerBounds=[25.505,91.845,25.565,91.905], viewpoint=[25.535,91.875], radiusM=30000, hills=True),
    R(id='sohra', name='Sohra · Cherrapunji', country='India', description='Cherrapunji cliffs and the Khasi hills · Meghalaya', codes=['IN', 'BT', 'CN', 'MM', 'BD'],
      observerBounds=[25.240,91.700,25.300,91.760], viewpoint=[25.27,91.73], radiusM=30000, hills=True),
    R(id='dzukou', name='Dzükou Valley', country='India', description='Dzükou valley and Japfu peak · Nagaland', codes=['IN', 'BT', 'CN', 'MM', 'BD'],
      observerBounds=[25.530,94.040,25.590,94.100], viewpoint=[25.56,94.07], radiusM=30000),
    R(id='shirui', name='Shirui Hill · Ukhrul', country='India', description='Shirui Kashung peak · Manipur', codes=['IN', 'BT', 'CN', 'MM', 'BD'],
      observerBounds=[25.080,94.420,25.140,94.480], viewpoint=[25.11,94.45], radiusM=30000),
    R(id='phawngpui', name='Phawngpui · Blue Mountain', country='India', description='Mizoram\'s highest peak · Mizoram', codes=['IN', 'BT', 'CN', 'MM', 'BD'],
      observerBounds=[22.600,93.010,22.660,93.070], viewpoint=[22.63,93.04], radiusM=30000),
    R(id='aizawl', name='Aizawl Hills', country='India', description='Aizawl ridge and Durtlang hills · Mizoram', codes=['IN', 'BT', 'CN', 'MM', 'BD'],
      observerBounds=[23.700,92.690,23.760,92.750], viewpoint=[23.73,92.72], radiusM=30000),
    R(id='jampui', name='Jampui Hills', country='India', description='Betlingshib and the Jampui range · Tripura', codes=['IN', 'BT', 'CN', 'MM', 'BD'],
      observerBounds=[23.870,92.240,23.930,92.300], viewpoint=[23.9,92.27], radiusM=30000),
    R(id='gangtok', name='Gangtok · Tashi Viewpoint', country='India', description='Gangtok views of Kangchenjunga · Sikkim', codes=['IN', 'NP', 'BT', 'CN'],
      observerBounds=[27.320,88.590,27.380,88.650], viewpoint=[27.35,88.62], radiusM=30000),
    R(id='pelling', name='Pelling', country='India', description='Pelling and Kangchenjunga views · Sikkim', codes=['IN', 'NP', 'BT', 'CN'],
      observerBounds=[27.270,88.210,27.330,88.270], viewpoint=[27.3,88.24], radiusM=30000),
    R(id='yumthang', name='Yumthang Valley', country='India', description='Yumthang and Lachung valleys · North Sikkim', codes=['IN', 'NP', 'BT', 'CN'],
      observerBounds=[27.800,88.670,27.860,88.730], viewpoint=[27.83,88.7], radiusM=30000),
    R(id='dzongri', name='Dzongri · Goecha La trek', country='India', description='Dzongri top on the Goecha La trek · Sikkim', codes=['IN', 'NP', 'BT', 'CN'],
      observerBounds=[27.450,88.140,27.510,88.200], viewpoint=[27.48,88.17], radiusM=30000),
    R(id='tsomgo', name='Tsomgo Lake', country='India', description='Tsomgo lake and Nathu La road · Sikkim', codes=['IN', 'NP', 'BT', 'CN'],
      observerBounds=[27.345,88.730,27.405,88.790], viewpoint=[27.375,88.76], radiusM=30000),
    R(id='tiger-hill', name='Tiger Hill · Darjeeling', country='India', description='Sunrise over Kangchenjunga · West Bengal', codes=['IN', 'NP', 'BT', 'CN'],
      observerBounds=[26.960,88.260,27.020,88.320], viewpoint=[26.99,88.29], radiusM=30000),
    R(id='sandakphu', name='Sandakphu', country='India', description='Sleeping Buddha and Everest views · West Bengal', codes=['IN', 'NP', 'BT', 'CN'],
      observerBounds=[27.076,87.970,27.136,88.030], viewpoint=[27.106,88.0], radiusM=30000),
    R(id='manali', name='Manali · Solang', country='India', description='Manali, Solang and the Pir Panjal · Himachal Pradesh', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[32.240,77.150,32.300,77.210], viewpoint=[32.27,77.18], radiusM=30000),
    R(id='rohtang', name='Rohtang Pass', country='India', description='Rohtang pass views · Himachal Pradesh', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[32.340,77.220,32.400,77.280], viewpoint=[32.37,77.25], radiusM=30000),
    R(id='kheerganga', name='Kheerganga · Parvati Valley', country='India', description='Kheerganga and the Parvati valley · Himachal Pradesh', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[31.960,77.480,32.020,77.540], viewpoint=[31.99,77.51], radiusM=30000),
    R(id='kaza', name='Kaza · Spiti', country='India', description='Kaza, Key monastery and Spiti valley · Himachal Pradesh', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[32.200,78.040,32.260,78.100], viewpoint=[32.23,78.07], radiusM=30000),
    R(id='shimla', name='Shimla · Jakhu', country='India', description='Jakhu hill and Shimla ridge · Himachal Pradesh', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[31.070,77.150,31.130,77.210], viewpoint=[31.1,77.18], radiusM=30000),
    R(id='dalhousie', name='Dalhousie · Dainkund', country='India', description='Dainkund peak and Dhauladhar views · Himachal Pradesh', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[32.510,75.980,32.570,76.040], viewpoint=[32.54,76.01], radiusM=30000),
    R(id='bir-billing', name='Bir Billing', country='India', description='Billing launch site and Dhauladhar · Himachal Pradesh', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[32.030,76.700,32.090,76.760], viewpoint=[32.06,76.73], radiusM=30000),
    R(id='leh', name='Leh · Shanti Stupa', country='India', description='Leh, Shanti Stupa and Stok Kangri views · Ladakh', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[34.140,77.550,34.200,77.610], viewpoint=[34.17,77.58], radiusM=30000, hills=True),
    R(id='khardung-la', name='Khardung La', country='India', description='Khardung La pass · Ladakh', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[34.250,77.570,34.310,77.630], viewpoint=[34.28,77.6], radiusM=30000, hills=True),
    R(id='pangong', name='Pangong Tso', country='India', description='Pangong lake shore · Ladakh', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[33.890,78.390,33.950,78.450], viewpoint=[33.92,78.42], radiusM=30000, hills=True),
    R(id='gulmarg', name='Gulmarg · Apharwat', country='India', description='Gulmarg and Apharwat peak · Jammu and Kashmir', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[34.020,74.350,34.080,74.410], viewpoint=[34.05,74.38], radiusM=30000),
    R(id='pahalgam', name='Pahalgam', country='India', description='Pahalgam and the Lidder valley · Jammu and Kashmir', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[33.980,75.290,34.040,75.350], viewpoint=[34.01,75.32], radiusM=30000),
    R(id='sonamarg', name='Sonamarg', country='India', description='Sonamarg and Thajiwas glacier · Jammu and Kashmir', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[34.270,75.260,34.330,75.320], viewpoint=[34.3,75.29], radiusM=30000),
    R(id='mussoorie', name='Mussoorie · Lal Tibba', country='India', description='Lal Tibba views of the Garhwal Himalaya · Uttarakhand', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[30.430,78.060,30.490,78.120], viewpoint=[30.46,78.09], radiusM=30000),
    R(id='har-ki-dun', name='Har Ki Dun', country='India', description='Har Ki Dun valley and Swargarohini · Uttarakhand', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[31.120,78.390,31.180,78.450], viewpoint=[31.15,78.42], radiusM=30000),
    R(id='kausani', name='Kausani', country='India', description='Kausani panorama of Trishul and Nanda Devi · Uttarakhand', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[29.810,79.570,29.870,79.630], viewpoint=[29.84,79.6], radiusM=30000),
    R(id='munsiyari', name='Munsiyari', country='India', description='Munsiyari and the Panchachuli peaks · Uttarakhand', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[30.040,80.210,30.100,80.270], viewpoint=[30.07,80.24], radiusM=30000),
    R(id='bedni-bugyal', name='Bedni Bugyal · Roopkund', country='India', description='Bedni meadows on the Roopkund trek · Uttarakhand', codes=['IN', 'CN', 'PK', 'NP'],
      observerBounds=[30.180,79.650,30.240,79.710], viewpoint=[30.21,79.68], radiusM=30000),
    R(id='munnar', name='Munnar · Anamudi', country='India', description='Munnar and Anamudi, highest peak in the south · Kerala', codes=['IN'],
      observerBounds=[10.060,77.030,10.120,77.090], viewpoint=[10.09,77.06], radiusM=30000),
    R(id='ooty', name='Ooty · Doddabetta', country='India', description='Doddabetta and the Nilgiris · Tamil Nadu', codes=['IN'],
      observerBounds=[11.370,76.710,11.430,76.770], viewpoint=[11.4,76.74], radiusM=30000),
    R(id='kodaikanal', name='Kodaikanal', country='India', description='Kodaikanal and the Palani hills · Tamil Nadu', codes=['IN'],
      observerBounds=[10.210,77.460,10.270,77.520], viewpoint=[10.24,77.49], radiusM=30000),
    R(id='chembra', name='Chembra Peak · Wayanad', country='India', description='Chembra peak in Wayanad · Kerala', codes=['IN'],
      observerBounds=[11.520,76.050,11.580,76.110], viewpoint=[11.55,76.08], radiusM=30000),
    R(id='kudremukh', name='Kudremukh', country='India', description='Kudremukh peak · Karnataka', codes=['IN'],
      observerBounds=[13.100,75.240,13.160,75.300], viewpoint=[13.13,75.27], radiusM=30000),
    R(id='mullayanagiri', name='Mullayanagiri', country='India', description='Karnataka\'s highest peak, Chikmagalur · Karnataka', codes=['IN'],
      observerBounds=[13.360,75.690,13.420,75.750], viewpoint=[13.39,75.72], radiusM=30000),
    R(id='tadiandamol', name='Tadiandamol · Coorg', country='India', description='Tadiandamol, highest peak in Coorg · Karnataka', codes=['IN'],
      observerBounds=[12.180,75.600,12.240,75.660], viewpoint=[12.21,75.63], radiusM=30000),
    R(id='araku', name='Araku Valley', country='India', description='Araku valley and the Eastern Ghats · Andhra Pradesh', codes=['IN'],
      observerBounds=[18.300,82.840,18.360,82.900], viewpoint=[18.33,82.87], radiusM=30000),
    R(id='mahabaleshwar', name='Mahabaleshwar', country='India', description='Mahabaleshwar and the Sahyadri · Maharashtra', codes=['IN'],
      observerBounds=[17.890,73.630,17.950,73.690], viewpoint=[17.92,73.66], radiusM=30000),
    R(id='kalsubai', name='Kalsubai', country='India', description='Maharashtra\'s highest peak · Maharashtra', codes=['IN'],
      observerBounds=[19.570,73.680,19.630,73.740], viewpoint=[19.6,73.71], radiusM=30000),
    R(id='rajmachi', name='Lonavala · Rajmachi', country='India', description='Rajmachi fort and the Sahyadri · Maharashtra', codes=['IN'],
      observerBounds=[18.800,73.370,18.860,73.430], viewpoint=[18.83,73.4], radiusM=30000),
    R(id='mount-abu', name='Mount Abu · Guru Shikhar', country='India', description='Guru Shikhar, highest point of the Aravalli · Rajasthan', codes=['IN'],
      observerBounds=[24.620,72.750,24.680,72.810], viewpoint=[24.65,72.78], radiusM=30000),
    R(id='saputara', name='Saputara', country='India', description='Saputara and the Sahyadri foothills · Gujarat', codes=['IN'],
      observerBounds=[20.550,73.720,20.610,73.780], viewpoint=[20.58,73.75], radiusM=30000),
    R(id='pachmarhi', name='Pachmarhi · Dhupgarh', country='India', description='Dhupgarh and the Satpura range · Madhya Pradesh', codes=['IN'],
      observerBounds=[22.440,78.400,22.500,78.460], viewpoint=[22.47,78.43], radiusM=30000),
    # --- Pakistan / Bhutan / Georgia ---
    R(id='fairy-meadows', name='Fairy Meadows · Nanga Parbat', country='Pakistan', description='Fairy Meadows and Beyal Camp · Himalaya', codes=['PK'],
      observerBounds=[35.36,74.56,35.40,74.60], viewpoint=[35.3792,74.58], radiusM=25000),
    R(id='hunza-eagles-nest', name="Hunza · Eagle's Nest", country='Pakistan', description='Duikar viewpoint above Karimabad · Karakoram', codes=['PK'],
      observerBounds=[36.31,74.65,36.35,74.71], viewpoint=[36.33,74.68], radiusM=30000),
    R(id='paro-taktsang', name='Paro · Tiger\'s Nest', country='Bhutan', description='Taktsang monastery trail · Himalaya', codes=['BT'],
      observerBounds=[27.47,89.33,27.52,89.40], viewpoint=[27.4917,89.3633], radiusM=25000),
    R(id='kazbegi', name='Kazbegi · Gergeti', country='Georgia', description='Gergeti Trinity Church and Mount Kazbek · Caucasus', codes=['GE'],
      observerBounds=[42.64,44.62,42.68,44.66], viewpoint=[42.66,44.64], radiusM=20000),
    # --- Japan ---
    R(id='fuji-kawaguchiko', name='Mount Fuji · Kawaguchiko', country='Japan', description='Lake Kawaguchi shoreline viewpoints · Fuji Five Lakes', codes=['JP'],
      observerBounds=[35.48,138.74,35.52,138.80], viewpoint=[35.50,138.76], radiusM=30000),
    R(id='kamikochi', name='Kamikochi · Japan Alps', country='Japan', description='Kappa Bridge and the Azusa valley', codes=['JP'],
      observerBounds=[36.23,137.60,36.27,137.68], viewpoint=[36.2497,137.6358], radiusM=20000),
    # --- Alps and Europe ---
    R(id='zermatt', name='Zermatt · Matterhorn', country='Switzerland', description='Zermatt and Gornergrat · Swiss Alps', codes=['CH','IT'],
      observerBounds=[45.96,7.72,46.02,7.80], viewpoint=[45.9836,7.7843], radiusM=15000),
    R(id='courmayeur', name='Courmayeur · Mont Blanc', country='Italy', description='Courmayeur and Val Ferret · Mont Blanc massif, Alps', codes=['IT','FR','CH'],
      observerBounds=[45.77,6.95,45.81,7.00], viewpoint=[45.79,6.97], radiusM=15000),
    R(id='grindelwald', name='Grindelwald · Jungfrau', country='Switzerland', description='First, Männlichen and Kleine Scheidegg · Bernese Alps', codes=['CH'],
      observerBounds=[46.62,7.95,46.68,8.06], viewpoint=[46.6592,8.0536], radiusM=20000),
    R(id='dolomites-tre-cime', name='Dolomites · Tre Cime', country='Italy', description='Tre Cime di Lavaredo loop · Dolomites, Alps', codes=['IT','AT'],
      observerBounds=[46.59,12.28,46.64,12.33], viewpoint=[46.6136,12.2952], radiusM=20000),
    R(id='lake-bled', name='Lake Bled · Triglav', country='Slovenia', description='Bled and the Julian Alps', codes=['SI'],
      observerBounds=[46.35,14.08,46.40,14.14], viewpoint=[46.3683,14.1146], radiusM=30000),
    R(id='zakopane', name='Zakopane · Tatras', country='Poland', description='Gubałówka and Kasprowy Wierch · Tatra Mountains', codes=['PL','SK'],
      observerBounds=[49.23,19.93,49.31,20.00], viewpoint=[49.2318,19.9816], radiusM=20000),
    R(id='innsbruck-nordkette', name='Innsbruck · Nordkette', country='Austria', description='Hafelekar and Seegrube · Tyrol, Alps', codes=['AT'],
      observerBounds=[47.26,11.36,47.32,11.42], viewpoint=[47.3122,11.3851], radiusM=20000),
    R(id='zugspitze', name='Zugspitze · Garmisch', country='Germany', description='Zugspitze and Garmisch-Partenkirchen · Bavarian Alps', codes=['DE','AT'],
      observerBounds=[47.40,10.98,47.50,11.10], viewpoint=[47.4211,10.9854], radiusM=20000),
    R(id='lake-district', name='Lake District · Keswick', country='United Kingdom', description='Keswick, Catbells and Skiddaw · Cumbrian fells', codes=['GB'],
      observerBounds=[54.50,-3.20,54.62,-3.05], viewpoint=[54.60,-3.13], radiusM=20000),
    # --- North America ---
    R(id='yosemite-glacier-point', name='Yosemite · Glacier Point', country='United States', description='Glacier Point and Yosemite Valley · Sierra Nevada', codes=['US'],
      observerBounds=[37.70,-119.62,37.76,-119.52], viewpoint=[37.7281,-119.5730], radiusM=25000),
    R(id='rocky-mountain-bear-lake', name='Rocky Mountain · Bear Lake', country='United States', description='Bear Lake and Longs Peak · Rocky Mountains', codes=['US'],
      observerBounds=[40.28,-105.70,40.40,-105.55], viewpoint=[40.3125,-105.6457], radiusM=25000),
    R(id='grand-teton', name='Grand Teton · Jenny Lake', country='United States', description='Jenny Lake and the Teton Range · Rocky Mountains', codes=['US'],
      observerBounds=[43.70,-110.78,43.80,-110.68], viewpoint=[43.7636,-110.7266], radiusM=15000),
    R(id='mount-rainier', name='Mount Rainier · Paradise', country='United States', description='Paradise and Skyline Trail · Cascade Range', codes=['US'],
      observerBounds=[46.76,-121.75,46.80,-121.70], viewpoint=[46.7865,-121.7353], radiusM=15000),
    R(id='glacier-logan-pass', name='Glacier · Logan Pass', country='United States', description='Logan Pass and Hidden Lake · Rocky Mountains', codes=['US'],
      observerBounds=[48.68,-113.72,48.72,-113.68], viewpoint=[48.6957,-113.7181], radiusM=20000),
    R(id='banff-lake-louise', name='Banff · Lake Louise', country='Canada', description='Lake Louise and Moraine Lake · Canadian Rockies', codes=['CA'],
      observerBounds=[51.40,-116.22,51.45,-116.14], viewpoint=[51.4254,-116.1773], radiusM=20000),
    # --- South America ---
    R(id='machu-picchu', name='Machu Picchu', country='Peru', description='Machu Picchu and Huayna Picchu · Andes', codes=['PE'],
      observerBounds=[-13.18,-72.57,-13.15,-72.52], viewpoint=[-13.1631,-72.545], radiusM=15000),
    R(id='el-chalten', name='El Chaltén · Fitz Roy', country='Argentina', description='El Chaltén and Laguna de los Tres · Patagonia, Andes', codes=['AR','CL'],
      observerBounds=[-49.35,-72.92,-49.30,-72.86], viewpoint=[-49.3317,-72.8862], radiusM=20000),
    # --- Africa ---
    R(id='kilimanjaro-moshi', name='Kilimanjaro · Moshi', country='Tanzania', description='Moshi town and the lower slopes of Kilimanjaro', codes=['TZ'],
      observerBounds=[-3.37,37.32,-3.33,37.36], viewpoint=[-3.35,37.34], radiusM=35000),
    R(id='table-mountain', name='Table Mountain · Cape Town', country='South Africa', description='Table Mountain and Lion\'s Head', codes=['ZA'],
      observerBounds=[-33.98,18.38,-33.93,18.44], viewpoint=[-33.96,18.40], radiusM=15000),
    # --- New Zealand ---
    R(id='queenstown', name='Queenstown · Remarkables', country='New Zealand', description='Queenstown and Bob\'s Peak · Southern Alps', codes=['NZ'],
      observerBounds=[-45.05,168.63,-45.00,168.70], viewpoint=[-45.03,168.66], radiusM=20000),
    R(id='aoraki-mount-cook', name='Aoraki · Mount Cook', country='New Zealand', description='Mount Cook village and Hooker Valley · Southern Alps', codes=['NZ'],
      observerBounds=[-43.75,170.08,-43.72,170.12], viewpoint=[-43.735,170.096], radiusM=25000),
]

import math
def tile_name(lat, lon): return f'{"N" if lat >= 0 else "S"}{abs(lat):02d}{"E" if lon >= 0 else "W"}{abs(lon):03d}'
def required_tiles(region):
    """Mirror RegionStore's coverage rule: every 1° cell touched by the observer box plus radius."""
    s, w, n, e = region['observerBounds']
    angular = math.degrees(region['radiusM'] / 6371008.8) + 1 / 3600
    margin = angular / math.cos(math.radians(max(abs(s), abs(n)) + angular))
    lats = range(math.floor(s - angular), math.floor(n + angular) + 1)
    lons = range(math.floor(w - margin), math.floor(e + margin) + 1)
    return [tile_name(lat, lon) for lat in lats for lon in lons], [lats[0], lons[0], lats[-1] + 1, lons[-1] + 1]
for region in REGIONS:
    region['tiles'], region['terrainBounds'] = required_tiles(region)
    assert -55 < region['observerBounds'][0] and region['observerBounds'][2] < 59, region['id']

def sha(data): return hashlib.sha256(data).hexdigest()
def bounds(values): return dict(zip(['south','west','north','east'],values))
def peaks(dumps, codes, extent, hills=False):
    rows=[]
    for code in codes:
        with zipfile.ZipFile(dumps/(code+'.zip')) as archive:
            rows += archive.read(code+'.txt').decode().splitlines()
    result=[]
    for line in rows:
        r=line.split('\t')
        if len(r)!=19 or r[6]!='T' or r[7] not in (('MT','PK','HLL') if hills else ('MT','PK')): continue
        lat,lon=float(r[4]),float(r[5])
        if not (extent[0]<=lat<=extent[2] and extent[1]<=lon<=extent[3]): continue
        # GeoNames DEM column is not a measured summit elevation. Do not use it.
        height=int(r[15]) if r[15] and -500<=int(r[15])<=10000 else None
        result.append(dict(id='geonames:'+r[0],name=r[1],latitude=lat,longitude=lon,
                           elevationM=height,countryCode=r[8],source='GeoNames (CC BY 4.0)',
                           sourceUrl=f'https://www.geonames.org/{r[0]}/',modified=r[18],featureCode=r[7]))
    return sorted(result,key=lambda p:p['id'])

def build(args):
    entries=[]
    selected = [r for r in REGIONS if not args.regions or r['id'] in args.regions]
    assert not args.regions or set(args.regions) == {r['id'] for r in selected}, 'Unknown region ID'
    for region in selected:
        region_peaks=peaks(args.dumps,region['codes'],region['terrainBounds'],region.get('hills',False))
        if len(region_peaks)<3:
            print('SKIP',region['id'],'has',len(region_peaks),'named peaks')
            continue
        directory=args.output/region['id']; directory.mkdir(parents=True,exist_ok=True)
        peak_data=json.dumps(region_peaks,ensure_ascii=False,indent=2).encode()
        tiles=[]; files=[]
        for name in region['tiles']:
            compressed=(args.tiles/(name+'.hgt.gz')).read_bytes()
            raw=gzip.decompress(compressed)
            assert len(raw)==3601*3601*2, name
            address=f'https://s3.amazonaws.com/elevation-tiles-prod/skadi/{name[:3]}/{name}.hgt.gz'
            tiles.append(dict(path='terrain/'+name+'.hgt',url=address,downloadBytes=len(compressed),downloadSha256=sha(compressed)))
            files.append(dict(path='terrain/'+name+'.hgt',size=len(raw),sha256=sha(raw)))
        credit=f'''PeakBud {region['name']} · catalog version 1
Terrain: Mapzen/Tilezen Skadi, 1 arc-second HGT, WGS84 / EGM96.
Global SRTM and GMTED2010 terrain data courtesy of the U.S. Geological Survey.
Mapzen / Tilezen: https://github.com/tilezen/joerd
Source and attribution: https://github.com/tilezen/joerd/blob/master/docs/attribution.md
Format: https://github.com/tilezen/joerd/blob/master/docs/formats.md
Terrain tiles may combine SRTM and other source data; not labelled NASA SRTMGL1 v003.
Verified tile downloads: {args.date}. Source bytes are pinned by SHA-256 in this app.
Peak names/coordinates: GeoNames, https://www.geonames.org/ (CC BY 4.0).
License: https://creativecommons.org/licenses/by/4.0/
Country dumps retrieved {args.date}:
'''+''.join(f'https://download.geonames.org/export/dump/{c}.zip  SHA-256 {sha((args.dumps/(c+".zip")).read_bytes())}\n' for c in region['codes'])+'''
Changes: selected single-mountain/peak records (MT/PK; HLL also included for broad hill regions), clipped to terrain bounds,
normalized fields, retained IDs/source links/modification dates; absent elevation stays null.
The GeoNames DEM column is not used as a published summit elevation.
Data is provided as is. Coverage and summit positions require field validation.
Tile source URLs:
'''+''.join(t['url']+'\n' for t in tiles)
        for name,data in [('peaks.json',peak_data),('attribution.txt',credit.encode())]:
            (directory/name).write_bytes(data)
            files.append(dict(path=name,size=len(data),sha256=sha(data)))
        view=region['viewpoint']
        manifest=dict(schemaVersion=1,id=region['id'],name=region['name'],version='1',horizontalCrs='EPSG:4326',verticalDatum='EGM96',
                      observerBounds=bounds(region['observerBounds']),terrainBounds=bounds(region['terrainBounds']),radiusM=region['radiusM'],
                      viewpoint=dict(latitude=view[0],longitude=view[1],headingDeg=0),source='Mapzen/Tilezen Skadi; GeoNames CC BY 4.0',preparedAt=args.date,files=files)
        entries.append(dict(id=region['id'],country=region['country'],description=region['description'],assetDirectory='regions/'+region['id'],tiles=tiles,manifest=manifest))
        print(region['name'],len(json.loads(peak_data)),'mountains',sum(t['downloadBytes'] for t in tiles),'download bytes')
    if args.regions and (args.output/'catalog.json').exists():
        existing = json.loads((args.output/'catalog.json').read_text())['regions']
        entries += [r for r in existing if r['id'] not in {v['id'] for v in entries}]
    (args.output/'catalog.json').write_text(json.dumps(dict(schemaVersion=1,regions=entries),ensure_ascii=False,indent=2)+'\n')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dumps',required=True,type=Path)
    parser.add_argument('--tiles',required=True,type=Path)
    parser.add_argument('--output',default='app/src/main/assets/regions',type=Path)
    parser.add_argument('--date')
    parser.add_argument('--regions',nargs='+',help='Build only these region IDs and preserve other existing entries')
    parser.add_argument('--list-tiles',action='store_true',help='Print required terrain tiles and exit')
    options=parser.parse_args()
    if options.list_tiles:
        for region in REGIONS: print(region['id'], ' '.join(region['tiles']))
        print('UNIQUE', ' '.join(sorted({t for r in REGIONS for t in r['tiles']})))
    else:
        if not options.date: parser.error('--date is required')
        build(options)
